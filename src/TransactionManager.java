import java.util.*;

public class TransactionManager {

    // Operations class which stores information about the operation
    private class Operation{
        public String opType;
        public int value;
        public String transaction;
        public String variable;

        public Operation(String opType,int value,String transaction, String variable){
            this.opType = opType;
            this.value = value;
            this.transaction = transaction;
            this.variable = variable;
        }
    }

    private Site[] sites;
    private int time;
    private int variables;
    private HashMap<String,Transaction> transactions;
    private HashMap<String,Set<String>> waitsForGraph;
    private HashMap<String,ArrayList<Operation>> lockWaitOperations;
    private ArrayList<Operation> failWaitOperations;
    private HashMap<Integer,List<Integer>> SiteFailHistory;

    TransactionManager(int siteCount,int variables){
        this.sites = new Site[siteCount];
        for(int i=0;i<siteCount;i++){
            sites[i] = new Site(i+1);
        }
        this.variables = variables;
        transactions = new HashMap<>();
        waitsForGraph = new HashMap<>();
        lockWaitOperations = new HashMap<>();
        failWaitOperations = new ArrayList<>();
        SiteFailHistory = new HashMap<>();
        time = 0;
    }

    public SuccessFail snapshotResult(Transaction t, String variable,int siteNo){
        SuccessFail result = new SuccessFail(false,0,t.getName());
        if(sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE)){
            List<Integer> time_data = sites[siteNo].finddata(variable, t.getStartTime());
            if(!SiteFailHistory.containsKey(siteNo)){
                result.value = time_data.get(1);
                result.status = true;
                return result;
            }
            List<Integer> tempFailList = SiteFailHistory.get(siteNo);
            if(tempFailList.get(tempFailList.size()-1) < time_data.get(0)){
                return result;
            }
            int low = 0;
            int high = time_data.size()-1;
            int mid;
            while(low<high){
                mid = (int)(low+high)/2;
                if(tempFailList.get(mid) >= time){
                    high = mid;
                }
                else{
                    low = mid+1;
                }
            }
            if(tempFailList.get(low) > time){
                result.value = time_data.get(1);
                result.status = true;
                return result;
            }
        }
        return result;
    }

    public SuccessFail readRequest(String transaction, String variable){
        Transaction t = transactions.get(transaction);
        SuccessFail result = new SuccessFail(false,0,transaction);
        if(lockWaitOperations.get(variable) != null){
            lockWaitOperations.get(variable).add(new Operation("R", -1,transaction,variable));
            waitsForGraph.get(transaction).add(result.transaction);
            return result;
        }
        if(t.isReadOnly()){
            int variableNo = Integer.parseInt(variable.substring(1,variable.length()));
            if(variableNo%2 == 1){
                result = snapshotResult(t, variable, variableNo%10);
            }
            else{
                for(int siteNo = 0; siteNo<10;siteNo++){
                    result = snapshotResult(t, variable, siteNo);
                    if(result.status){
                        break;
                    }
                }
            }
            if(!result.status) failWaitOperations.add(new Operation("R",-1,transaction, variable));
            return result;
        }
        else{
            int siteNo = -1;
            int variableNo = Integer.parseInt(variable.substring(1, variable.length()-1));
            if(variableNo%2 == 1){
                siteNo = (variableNo%10);
                result = sites[siteNo].readdata(transaction,variable);
            }
            else{
                do{
                    siteNo += 1;
                    result = sites[siteNo].readdata(transaction,variable);
                }while(siteNo < 10 && !result.status && !sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE));
            }

            if(result.status) transactions.get(transaction).addToLocktable(variable, "R");
            else {
                if(siteNo < 10 && sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE)){
                    if(waitsForGraph.get(transaction) == null)
                        waitsForGraph.put(transaction,new HashSet<>());
                    waitsForGraph.get(transaction).add(result.transaction);
                    SuccessFail cycle_result = check_deadlock();
                    if(cycle_result.status){
                        abort(cycle_result.transaction);
                    }
                }

                if(siteNo == 10 || sites[siteNo].getStatus().equals(Site.SiteStatus.FAIL)) {
                    failWaitOperations.add(new Operation("R",-1,transaction, variable));    
                }
                else{
                    if(lockWaitOperations.get(variable) == null)
                        lockWaitOperations.put(variable,new ArrayList<>());
                    lockWaitOperations.get(variable).add(new Operation("R",-1,transaction, variable));
                }
            }

            return result;
        }
    }

    public SuccessFail writeRequest(String transaction, String variable, int value){
        Transaction t = transactions.get(transaction);
        SuccessFail result = new SuccessFail(false,value,transaction);
        if(lockWaitOperations.get(variable) != null){
            lockWaitOperations.get(variable).add(new Operation("W", value,transaction, variable));
            waitsForGraph.get(transaction).add(result.transaction);
            return result;
        }
        int siteNo = 0;
        boolean islocked = false;
        boolean isactive = false;

        int variableNo = Integer.parseInt(variable.substring(1, variable.length()));
        if(variableNo%2 == 1){
            siteNo = (variableNo%10);
            result = sites[siteNo].writedata(transaction,variable,value);
            if(sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE)){
                isactive = true;
                if(!result.status){
                    islocked = true;
                }
            }
        }
        else{
            while(siteNo<10){
                result = sites[siteNo].canGetWriteLock(transaction, variable);
                if(sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE) || sites[siteNo].getStatus().equals(Site.SiteStatus.RECOVER)){
                    isactive = true;
                    if(!result.status){
                        islocked = true;
                        break;
                    }
                }
                siteNo++;
            }

            siteNo = 0;
            while(!islocked && isactive && siteNo<10){
                if(sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE)){
                    result = sites[siteNo].writedata(transaction, variable, value);
                }
                siteNo++;
            }
        }

        if(result.status) transactions.get(transaction).addToLocktable(variable, "W");
        else if(!isactive) failWaitOperations.add(new Operation("W",value,transaction, variable));
        else if(islocked) {

            //Add to waitsforgraph and check/remove deadlock
            result.status = false;
            if(waitsForGraph.get(transaction) == null)
                waitsForGraph.put(transaction,new HashSet<>());
            waitsForGraph.get(transaction).add(result.transaction);
            SuccessFail cycle_result = check_deadlock();
            if(cycle_result.status){
                abort(cycle_result.transaction);
            }

            //Add to lockWait table
            if(lockWaitOperations.get(variable) == null)
                lockWaitOperations.put(variable,new ArrayList<>());
            lockWaitOperations.get(variable).add(new Operation("W",value,transaction, variable));
        }

        return result;
    }

    public void beginTransaction(String transaction){
        time += 1;
        Transaction t = new Transaction(transaction, false, Status.ACTIVE, time);
        transactions.put(transaction,t);
    }
    public void beginROTransaction(String transaction){
        time += 1;
        Transaction t = new Transaction(transaction, true, Status.ACTIVE, time);
        transactions.put(transaction,t);
    }

    private void release_locks(String transaction,boolean commit) {
        HashMap<String,String> locktable = transactions.get(transaction).getLocktable();
        for(Map.Entry<String,String> lock: locktable.entrySet()) {
            String variable = lock.getKey();
            int variablenumber = Integer.parseInt(variable.substring(1, variable.length()));

            if(variablenumber%2 == 1) {
                sites[variablenumber%10].removeLock(variable, transaction, commit, time);
            }
            else {
                for(int siteNo = 0; siteNo<10; siteNo++){
                    sites[siteNo].removeLock(variable, transaction,commit,time);
                }
            }
            
            boolean check = true;
            SuccessFail result = new SuccessFail(false, -1, transaction);
            while(lockWaitOperations.containsKey(variable) && check){
                
                Operation nextOperation = lockWaitOperations.get(variable).remove(0);
                if(lockWaitOperations.get(variable).isEmpty()) lockWaitOperations.remove(variable);

                if(nextOperation.opType == "R"){
                    result = readRequest(nextOperation.transaction, variable);
                }
                else if(nextOperation.opType == "W"){
                    result = writeRequest(nextOperation.transaction, variable, nextOperation.value);
                }
                check = result.status;
            }
        }
    }

    public void endTransaction(String transaction){
        time++;
        if(transactions.get(transaction).getStatus().equals(Status.ACTIVE)){
            commit(transaction);
        }
        else if(transactions.get(transaction).getStatus().equals(Status.TO_BE_ABORTED)){
            abort(transaction);
        }
    }

    public void commit(String transaction){
        waitsForGraph.remove(transaction);
        for(Map.Entry<String,Set<String>> mapElement : waitsForGraph.entrySet()) { 
            mapElement.getValue().remove(transaction);
        }
       release_locks(transaction,true);
    }
    
    public void abort(String transaction) {
        transactions.get(transaction).setStatus(Status.ABORTED);

        //Remove transaction from waitsforgraph
        waitsForGraph.remove(transaction);
        for(Map.Entry<String,Set<String>> mapElement : waitsForGraph.entrySet()) { 
            mapElement.getValue().remove(transaction);
        }

        //Remove transaction from lockWaitOperations
        for(Map.Entry<String,ArrayList<Operation>> mapElement : lockWaitOperations.entrySet()) { 
            mapElement.getValue().removeIf(operation -> operation.transaction.equals(transaction));
        }
        lockWaitOperations.entrySet().removeIf(entry -> lockWaitOperations.get(entry.getKey()).isEmpty()); 
  
       release_locks(transaction,false);
    }

    private SuccessFail dfs(String u, HashSet<String> visited, HashSet<String> recursion_stack, String youngest_transaction) {
        visited.add(u);
        recursion_stack.add(u);
        if(transactions.get(u).getStartTime() > transactions.get(youngest_transaction).getStartTime()) youngest_transaction = u;
        for(String v : waitsForGraph.getOrDefault(u,  Collections.<String>emptySet())) {
            if(!visited.contains(v)) {
                SuccessFail cycle_result = dfs(v,visited,recursion_stack,youngest_transaction);
                if(cycle_result.status) return cycle_result;
            } 
            else if(recursion_stack.contains(v)) return new SuccessFail(true,-1,youngest_transaction);
        }
        recursion_stack.remove(u);
        return new SuccessFail(false, -1, youngest_transaction);
    }

    private SuccessFail check_deadlock(){
        HashSet<String> visited = new HashSet<>();
        HashSet<String> recursion_stack = new HashSet<>();
        for(Map.Entry<String,Set<String>> mapElement : waitsForGraph.entrySet()) { 
            String u = (String)mapElement.getKey(); 
            if(!visited.contains(u)) {
                SuccessFail cycle_result = dfs(u, visited,recursion_stack, u);
                if(cycle_result.status) return cycle_result;
            } 
        } 
        return new SuccessFail(false, -1, "");
    }

    public void fail(int site){
        time++;
        if(!SiteFailHistory.containsKey(site)){
            SiteFailHistory.put(site, new ArrayList<>());
        }
        SiteFailHistory.get(site).add(time);
        sites[site].abortornot(transactions);
        sites[site-1].changeStatus(Site.SiteStatus.FAIL);
    }

    public void recover(int site){
        time++;
        sites[site-1].changeStatus(Site.SiteStatus.RECOVER);
        
        Iterator<Operation> itr = failWaitOperations.iterator(); 
        while (itr.hasNext()) { 
            Operation operation = itr.next(); 
            int variableNo = Integer.parseInt(operation.variable.substring(1,operation.variable.length()));
            if (variableNo%2 == 0 || variableNo%10 + 1 == site) { 
                if(operation.opType == "R") 
                    readRequest(operation.transaction, operation.variable);
                else if(operation.opType == "W") 
                    writeRequest(operation.transaction, operation.variable, operation.value);
                itr.remove(); 
            } 
        }
    }

    public void dump(){
        for(int siteNo=0; siteNo<10;siteNo++){
            System.out.print("site " + (Integer.toString(siteNo+1)) +" - ");
            for(int varNo = 1;varNo<=20;varNo++){
                String variable = "x".concat(Integer.toString(varNo));
                if(varNo%2 == 1 && siteNo != varNo%10){
                    continue;
                }
                System.out.print(variable+": "+Integer.toString(sites[siteNo].getCommit(variable))+", ");
            }
            System.out.println();
        }
    }

    public int getVar(){
        return variables;
    }
    public Site getSite(int siteno){
        return sites[siteno];
    }
}
