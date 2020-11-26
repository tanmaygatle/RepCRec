import java.util.ArrayList;
import java.util.HashMap;

public class TransactionManager {
    private class Operation{
        public String opType;
        public int value;
        public String transaction;
        public Operation(String opType,int value,String transaction){
            this.opType = opType;
            this.value = value;
            this.transaction = transaction;
        }
    }
    private Site[] sites;
    private int time;
    private int variables;
    private HashMap<String,Transaction> transactions;
    private HashMap<String,ArrayList<Transaction>> waitsForGraph;
    private HashMap<String,ArrayList<Operation>> waitOperations;
    public void initialData(){
        for(int i = 1;i<=20;i++){
            if(i%2 == 1){
                if(!sites[(i%10)].dm.writeData("x".concat(Integer.toString(i)), 10*(i))){
                    System.out.println("Couldn't write Data");
                }
            }
            else{
                for(int j = 0;j<this.sites.length;j++){
                    if(!sites[j].dm.writeData("x".concat(Integer.toString(i)), 10*(i))){
                        System.out.println("Couldn't write Data");
                    }
                }
            }
        }
    }
    TransactionManager(int siteCount,int variables){
        this.sites = new Site[siteCount];
        this.variables = variables;
        transactions = new HashMap<>();
        waitsForGraph = new HashMap<>();
        time = 0;
    }
    public SuccessFail readRequest(String transaction, String variable){
        Transaction t = transactions.get(transaction);
        t.addOperation("Read",variable);
        SuccessFail result = new SuccessFail(false,0,transaction);
        if(waitOperations.get(variable) != null){
            waitOperations.get(variable).add(new Operation("R", -1,transaction));
            return result;
        }
        if(t.isReadOnly()){
            result.status = true;
            result.value = t.getVal(variable);
            result.transaction = transaction;
            return result;
        }
        else{
            int siteNo = -1;
            int variableNo = Integer.parseInt(variable.substring(1, variable.length()-1));
            if(variableNo%2 == 1){
                siteNo = (variableNo%10);
                result = sites[siteNo].readdata(t,variable);
            }
            else{
                do{
                    siteNo += 1;
                    result = sites[siteNo].readdata(t,variable);
                }while(siteNo < 10 && !result.status && !sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE));
            }
            if(!result.status){
                if(siteNo < 10 && sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE)){
                    if(waitsForGraph.get(transaction) == null)
                        waitsForGraph.put(transaction,new ArrayList<>());
                    waitsForGraph.get(transaction).add(transactions.get(result.transaction));
                }
                if(waitOperations.get(variable) == null)
                    waitOperations.put(variable,new ArrayList<>());
                    // deadlock check;
                waitOperations.get(variable).add(new Operation("R",-1,transaction));
            }
            return result;
        }
    }

    public SuccessFail writeRequest(String transaction, String variable, int value){
        Transaction t = transactions.get(transaction);
        t.addOperation("Write",variable);
        SuccessFail result = new SuccessFail(false,value,transaction);
        if(waitOperations.get(variable) != null){
            waitOperations.get(variable).add(new Operation("W", value,transaction));
            return result;
        }
        int siteNo = 0;
        boolean locked = false;
        boolean activity = false;
        int variableNo = Integer.parseInt(variable.substring(1, variable.length()-1));
        if(variableNo%2 == 1){
            siteNo = (variableNo%10);
            result = sites[siteNo].writedata(t,variable,value);
            if(sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE)){
                if(!result.status){
                    locked = true;
                }
                else{
                    activity = true;
                }
            }
        }
        else{
            while(siteNo<10){
                result = sites[siteNo].canGetWriteLock(t, variable);
                if(sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE)){
                    if(!result.status){
                        locked = true;
                        break;
                    }
                    activity = true;
                }
                siteNo ++;
            }
            // if(siteNo != 10) return result;

            int siteNoTemp = 0;
            if(activity && siteNo == 10){
                while(siteNoTemp<10){
                    if(sites[siteNoTemp].getStatus().equals(Site.SiteStatus.ACTIVE)){
                        result = sites[siteNoTemp].writedata(t, variable, value);
                    }
                    siteNoTemp ++;
                }
            }

        }
        if(locked){
            result.status = false;
            if(waitsForGraph.get(transaction) == null)
                waitsForGraph.put(transaction,new ArrayList<>());
            waitsForGraph.get(transaction).add(transactions.get(result.transaction));
        }
        if(!activity){
            result.status = false;
            if(waitOperations.get(variable) == null)
                waitOperations.put(variable,new ArrayList<>());
                // deadlock check;
            waitOperations.get(variable).add(new Operation("W",value,transaction));
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
        int siteNo = -1;
        Transaction t = new Transaction(transaction, true, Status.ACTIVE, time);
        do{
            siteNo += 1;
        }while(siteNo < 10 && !sites[siteNo].getStatus().equals(Site.SiteStatus.ACTIVE));
        if(siteNo<10){
            t.addToSnapshot(sites[siteNo].getDB());
        }
        // abort transaction if all sites down i.e siteNo = 10
        transactions.put(transaction,t);
    }
    public void fail(int site){
        sites[site-1].changeStatus(Site.SiteStatus.FAIL);
        // all transactions that accessed this site to be aborted
    }
    public void recover(int site){
        sites[site-1].changeStatus(Site.SiteStatus.RECOVER);
    }
    public int getVar(){
        return variables;
    }
    public Site getSite(int siteno){
        return sites[siteno];
    }
}