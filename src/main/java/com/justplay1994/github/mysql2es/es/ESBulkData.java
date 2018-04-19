package com.justplay1994.github.mysql2es.es;//package com.justplay1994.github.mysql2es;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.justplay1994.github.mysql2es.Mysql2es;
import com.justplay1994.github.mysql2es.database.DatabaseNode;
import com.justplay1994.github.mysql2es.database.DatabaseNodeListInfo;
import com.justplay1994.github.mysql2es.database.TableNode;
import com.justplay1994.github.mysql2es.http.client.urlConnection.MyURLConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by JustPlay1994 on 2018/4/2.
 * https://github.com/JustPlay1994/daily-log-manager
 */


/**
 * 批量数据插入。
 * 沿用之前单条数据插入的方式，用数据桶去接，到一定的大小则开始关闭数据接收，并执行数据插入操作。
 * 或者获取所有的数据后，在起线程执行。这样对调用方来说更透明。
 */
public class ESBulkData{
    String ESUrl;
    List<DatabaseNode> rows;/*批量数据*/
    public static final Logger logger = LoggerFactory.getLogger(ESBulkData.class);
    StringBuilder json = new StringBuilder();/*StringBuild更快，单线程使用*/
    ObjectMapper objectMapper = new ObjectMapper();

    public ESBulkData(String ESUrl, List<DatabaseNode> rows) {
        this.ESUrl = ESUrl;
        this.rows = rows;
    }



    public void inputData(){
        /*TODO 待优化，构造的请求体，执行了就删掉*/
//        新的方式：使用线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Mysql2es.maxThreadCount,
                10,
                200,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(8)     //等待队列
        );

        /*用于按块大小，切分批量数据请求*/
        int last = 0;/*上一次对块数据量整除，得到的值*/
        int now = 0;/*当前对块数据量整除，得到的值*/

        int blockRowNumber = 0;/*当前数据块的数据量大小，行数*/

        StringBuilder json = new StringBuilder();
        long jsonSize = 0;
        /*遍历数据，构造Mapping*/
        Iterator<DatabaseNode> databaseNodeIt = rows.iterator();
        while(databaseNodeIt.hasNext()) {
            DatabaseNode databaseNode = databaseNodeIt.next();
            Iterator<TableNode> tableNodeIterator = databaseNode.getTableNodeList().iterator();
            while (tableNodeIterator.hasNext()) {

                TableNode tableNode = tableNodeIterator.next();
                //每张表先创建mapping关系，创建location为地理信息点类型
                String mapping =
                        "        {\n" +
                                "            \"mappings\": {\n" +
                                "                \"_doc\": {\n" +
                                "                    \"properties\": {\n" +
                                "                        \"location\": { \"type\": \"geo_point\"  }\n" +
                                "                    }\n" +
                                "                }\n" +
                                "            }\n" +
                                "        }";
                createMapping(Mysql2es.indexName(databaseNode.getDbName(), tableNode.getTableName()), mapping);
            }
        }
        /*遍历数据，构造请求参数*/
        databaseNodeIt = rows.iterator();
        while(databaseNodeIt.hasNext()){
            DatabaseNode databaseNode = databaseNodeIt.next();
            Iterator<TableNode> tableNodeIterator = databaseNode.getTableNodeList().iterator();
            while(tableNodeIterator.hasNext()){
                TableNode tableNode = tableNodeIterator.next();
                /*找到经纬度的下标*/
                int lat=-1;
                int lon=-1;
                /*找到id下标*/
                int id=-1;

                /*遍历列名，找到经纬度字段对应下标*/
                for(int i = 0; i < tableNode.getColumns().size(); ++i){

                    if(Mysql2es.latStr.equals(tableNode.getColumns().get(i))){
                        lat=i;
                    }else if ((Mysql2es.lonStr.equals(tableNode.getColumns().get(i)))){
                        lon=i;
                    }else if("id".equalsIgnoreCase(tableNode.getColumns().get(i))) {
                        id=i;
                    }else{
                        if(lat!=-1 && lon!=-1 && id!=-1)break;
                    }
                }

                /*遍历数据*/
                Iterator<ArrayList<String>> iterator = tableNode.getRows().iterator();
                while (iterator.hasNext()) {
                    blockRowNumber++;

                    Map map = new HashMap();/*数据*/
                    ArrayList<String> row = iterator.next();
                    for(int i = 0; i < row.size(); ++i){
                        map.put(tableNode.getColumns().get(i),row.get(i));

                    }
                    /*根据已找到的经纬度字段，进行格式转换*/
                    if(lat!=-1 && lon!=-1){
                        HashMap location = new HashMap();
                        location.put("lat",row.get(lat));
                        location.put("lon",row.get(lon));
                        map.put("location",location);
                    }
                    /*请求head*/
                    if(id!=-1) {
                        json.append("{ \"index\":{ \"_index\": \"" + Mysql2es.indexName(databaseNode.getDbName(), tableNode.getTableName()) + "\", \"_type\": \"_doc\", \"_id\": "+row.get(id)+"}}\n");
                    }else{
                        json.append("{ \"index\":{ \"_index\": \"" + Mysql2es.indexName(databaseNode.getDbName(), tableNode.getTableName()) + "\", \"_type\": \"_doc\"}}\n");
                    }

                    /*请求body*/
                    ObjectMapper objectMapper = new ObjectMapper();
                    try {
                        json.append(objectMapper.writeValueAsString(map) + "\n");
                    } catch (JsonProcessingException e) {
                        logger.error("To json error!",e);
                    }
                    /*等待请求body满一个数据块大小，便开始执行请求*/
                    if(json.length()>=Mysql2es.BULKSIZE) {

                        //以前多线程方式：增加线程处理
//                        new Thread(new ESBulkDataThread(ESUrl, json.substring(index), blockRowNumber)).start();
                        //新的方式：以线程池方式启动
//                        executor.execute(new Thread(new ESBulkDataThread(ESUrl, json.substring(index), blockRowNumber)));
//                        index=json.length()-1;

                        /*为了节约内存空间，每次请求完成，则删除请求体*/
                        executor.execute(new Thread(new ESBulkDataThread(ESUrl, json.toString(), blockRowNumber)));
                        jsonSize+=json.length();
                        json.delete(0,json.length());


                        ESBulkDataThread.threadCount++;
                        while(ESBulkDataThread.threadCount>=Mysql2es.maxThreadCount){
                            logger.debug("max Thread:"+ESBulkDataThread.threadCount);
//                            logger.debug("线程池中线程数目："+executor.getPoolSize()+"，队列中等待执行的任务数目："+executor.getQueue().size()+"，已执行玩别的任务数目："+executor.getCompletedTaskCount());
                            long time = 200;
                            try {
                                Thread.sleep(time);
                            } catch (InterruptedException e) {
                                logger.error("sleep error!",e);
                            }
                        }
                        blockRowNumber = 0;
                    }
                }
                /*将每张表剩下的数据，执行插入数据*/
//                if(index != json.length()-1) {/*如果剩余数据是空，则不执行*/
//                    last = now;
//                    ESBulkDataThread.threadCount++;
//                    //以前多线程方式：增加线程处理
//                    new Thread(new ESBulkDataThread(ESUrl, json.substring(index), blockRowNumber)).start();
//                    //新的方式：以线程池方式启动
//                    //executor.execute(new Thread(new ESBulkDataThread(ESUrl, json.substring(index), blockRowNumber)));
//                    index = json.length() - 1;
//                    blockRowNumber = 0;
//                }
            }
        }

        /*阻塞等待线程结束*/
        while(ESBulkDataThread.threadCount!=0){
            logger.info("wait thread number : " + ESBulkDataThread.threadCount);
            long time = 1000;
            try {
                Thread.sleep(time);
            } catch (InterruptedException e) {
                logger.error("sleep error!",e);
            }
        }



        /*关闭线程池*/
//        executor.shutdown();

        logger.info("========================");
        checkAllData();
        logger.info("All finished!");
        logger.info("========================");
        logger.info("dbNumber: "+ DatabaseNodeListInfo.dbNumber);
        logger.info("tbNumber: "+DatabaseNodeListInfo.tbNumber);
        logger.info("rowNumber: "+DatabaseNodeListInfo.rowNumber);
        logger.info("total bulk body size(MB): "+jsonSize/1024/1024);
        logger.info("========================");

    }

    public void createMapping(String indexName, String mapping){
        logger.info("creating mapping...");

        /*创建索引映射*/

        try {
            new MyURLConnection().request(ESUrl + indexName,"PUT",mapping);
            logger.info("mapping finished! indexName: "+ indexName);
        } catch (MalformedURLException e) {
            logger.error("【MappingError】", e);
            logger.error("url: "+ESUrl+indexName+", "+ mapping);
        } catch (ProtocolException e) {
            logger.error("【MappingError】", e);
            logger.error("url: "+ESUrl+indexName+", "+ mapping);
        } catch (IOException e) {
            logger.error("【MappingError】", e);
            logger.error("url: "+ESUrl+indexName+", "+ mapping);
        }
    }

    public void checkAllData(){
        long esTotalRowNumber = 0;
        logger.info("check data number ...");
        /*查询es相关索引的数据量，验证数据量是否一致*/
        Iterator<DatabaseNode>  databaseNodeIt = rows.iterator();
        while(databaseNodeIt.hasNext()) {
            DatabaseNode databaseNode = databaseNodeIt.next();
            Iterator<TableNode> tableNodeIterator = databaseNode.getTableNodeList().iterator();

//            /*判断库的数据量与插入es中对应索引的数据量是否一致,TODO 如果es能直接查询到库的数据量（多个索引，索引通配符），可剪枝。*/
//            String result = new MyURLConnection().request(ESUrl + Mysql2es.indexName(databaseNode.getDbName(),tableNode.getTableName()) + "/_search", "POST", "");
//            if(databaseNode.getRowNumber() == )

            while (tableNodeIterator.hasNext()) {

                TableNode tableNode = tableNodeIterator.next();
                try {
                    String result = new MyURLConnection().request(ESUrl + Mysql2es.indexName(databaseNode.getDbName(),tableNode.getTableName()) + "/_search", "POST", "");
                    Map map = objectMapper.readValue(result,Map.class);
                    long esNumber = Integer.parseInt(((LinkedHashMap)map.get("hits")).get("total").toString());
                    esTotalRowNumber+=esNumber;
                    if(tableNode.getRows().size()!=esNumber){
                        logger.error("check number is not consistent! dbName= "+databaseNode.getDbName()+
                                ", tbName= "+tableNode.getTableName()+", tbRowNumber= "+tableNode.getRows().size()+
                                ", esRowNumber= "+esNumber);
                    }

                } catch (IOException e) {
                    logger.error("[check number error]",e);
                }
            }
        }
        logger.info("es total row number: "+esTotalRowNumber);
        logger.info("check data number finished !");
    }

}
