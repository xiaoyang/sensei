package com.sensei.indexing.api;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.log4j.Logger;
import org.json.JSONObject;

import proj.zoie.api.DataConsumer;
import proj.zoie.api.DefaultZoieVersion;
import proj.zoie.api.Zoie;
import proj.zoie.api.ZoieException;
import proj.zoie.api.DataConsumer.DataEvent;
import proj.zoie.impl.indexing.StreamDataProvider;
import proj.zoie.mbean.DataProviderAdmin;
import proj.zoie.mbean.DataProviderAdminMBean;

import com.browseengine.bobo.api.BoboIndexReader;
import com.sensei.conf.SenseiSchema;
import com.sensei.dataprovider.file.LinedJsonFileDataProvider;
import com.sensei.dataprovider.kafka.KafkaJsonStreamDataProvider;
import com.sensei.search.nodes.SenseiIndexingManager;

public class DefaultStreamingIndexingManager implements SenseiIndexingManager<JSONObject,DefaultZoieVersion> {

	private static final Logger logger = Logger.getLogger(DefaultStreamingIndexingManager.class);

	private static final String CONFIG_PREFIX = "sensei.index.manager.default";
	
	private static final String MAX_PARTITION_ID = "maxpartition.id";
	
	private static final String PROVIDER_TYPE = "type";
	
	
	private StreamDataProvider<JSONObject, DefaultZoieVersion> _dataProvider;
	private DefaultZoieVersion _oldestSinceKey;
	private final SenseiSchema _senseiSchema;
	private final Configuration _myconfig;
	private final List<ObjectName> _registeredMBeans;
	private final MBeanServer _mbeanServer;
	private Map<Integer, Zoie<BoboIndexReader, JSONObject, DefaultZoieVersion>> _zoieSystemMap;
	private final LinkedHashMap<Integer, Collection<DataEvent<JSONObject, DefaultZoieVersion>>> _dataCollectorMap;
	
	public DefaultStreamingIndexingManager(SenseiSchema schema,Configuration senseiConfig){
	  _dataProvider = null;
	  _myconfig = senseiConfig.subset(CONFIG_PREFIX);
	  _oldestSinceKey = null;
	  _senseiSchema = schema;
	  _zoieSystemMap = null;
	  _mbeanServer = ManagementFactory.getPlatformMBeanServer();
	  _registeredMBeans = new LinkedList<ObjectName>();
	  _dataCollectorMap = new LinkedHashMap<Integer, Collection<DataEvent<JSONObject, DefaultZoieVersion>>>();
	}

	public void updateOldestSinceKey(DefaultZoieVersion sinceKey){
	    if(_oldestSinceKey == null){
	      _oldestSinceKey = sinceKey;
	    }
	    else if(sinceKey!=null && sinceKey.compareTo(_oldestSinceKey) <0 ){
	      _oldestSinceKey = sinceKey;
	    }
	}
	
	@Override
	public void initialize(
			Map<Integer, Zoie<BoboIndexReader, JSONObject, DefaultZoieVersion>> zoieSystemMap)
			throws Exception {

		int maxPartitionId = _myconfig.getInt(MAX_PARTITION_ID)+1;
		String uidField = _senseiSchema.getUidField();
		DataDispatcher consumer = new DataDispatcher(maxPartitionId,uidField);
		
		_zoieSystemMap = zoieSystemMap;
		
	    Iterator<Integer> it = zoieSystemMap.keySet().iterator();
	    while(it.hasNext()){
	      int part = it.next();
	      Zoie<BoboIndexReader,JSONObject,DefaultZoieVersion> zoie = zoieSystemMap.get(part);
	      updateOldestSinceKey(zoie.getVersion());
	      _dataCollectorMap.put(part, new LinkedList<DataEvent<JSONObject, DefaultZoieVersion>>());
	    }
	    
	    
	    _dataProvider = buildDataProvider();
	    _dataProvider.setDataConsumer(consumer);
	}
	
	private StreamDataProvider<JSONObject, DefaultZoieVersion> buildDataProvider() throws ConfigurationException{
		String type = _myconfig.getString(PROVIDER_TYPE);
		StreamDataProvider<JSONObject,DefaultZoieVersion> dataProvider = null;
		if ("file".equals(type)){
			String path = _myconfig.getString("file.path");
			long offset = _oldestSinceKey == null ? 0L : Long.parseLong(_oldestSinceKey.encodeToString());
			dataProvider = new LinedJsonFileDataProvider(new File(path), offset);
		}
		else if ("kafka".equals(type)){
			String host = _myconfig.getString("kafka.host");
			int port = _myconfig.getInt("kafka.port");
			String topic = _myconfig.getString("kafka.topic");
			int timeout = _myconfig.getInt("kafka.timeout",10000);
			int batchsize = _myconfig.getInt("kafka.batchsize");
			long offset = _oldestSinceKey == null ? 0L : Long.parseLong(_oldestSinceKey.encodeToString());
			dataProvider = new KafkaJsonStreamDataProvider(host,port,timeout,batchsize,topic,offset);
		}
		else{
			throw new ConfigurationException("type: "+type+" is not suported");
		}
		
		try {
		   ObjectName dataProviderMBeanName = new ObjectName("senseidb","indexing-manager","stream-data-provider");
		   StandardMBean dataProviderMbean = new StandardMBean(new DataProviderAdmin(dataProvider), DataProviderAdminMBean.class);
		   _mbeanServer.registerMBean(dataProviderMbean, dataProviderMBeanName);
		   _registeredMBeans.add(dataProviderMBeanName);
		} catch (Exception e) {
		  logger.error(e.getMessage(),e);
		} 
		return dataProvider;
	}

	@Override
	public void shutdown() {
		try{
		  _dataProvider.stop();
		}
		finally{
		  for (ObjectName mbeanName : _registeredMBeans){
		    try {
				_mbeanServer.unregisterMBean(mbeanName);
			} catch (Exception e) {
				logger.error(e.getMessage(),e);
			} 
		  }
		}
	}

	@Override
	public void start() throws Exception {
		if (_dataProvider==null){
			throw new Exception("data provider is not started");
		}
		_dataProvider.start();
	}

	private class DataDispatcher implements DataConsumer<JSONObject, DefaultZoieVersion>
	  {
	    int _maxPartitionId;  // the total number of partitions over all the nodes;
	    private final String _uidField;
	    private volatile DefaultZoieVersion _currentVersion;
	    
	    public DataDispatcher(int maxPartitionId,String uidField){
	      _maxPartitionId = maxPartitionId;
	      _uidField = uidField;
	      _currentVersion = null;
	    }
	    
	    @Override
	    public void consume(Collection<proj.zoie.api.DataConsumer.DataEvent<JSONObject, DefaultZoieVersion>> data) throws ZoieException
	    {
	      try{
	        for(DataEvent<JSONObject, DefaultZoieVersion> dataEvt : data){
	    	  JSONObject obj = dataEvt.getData();
	    	  _currentVersion = dataEvt.getVersion();
	    	  long uid = obj.getLong(_uidField);
	    	  int routeToPart = (int)(uid % _maxPartitionId);
	          if(uid>=0 && DefaultStreamingIndexingManager.this._dataCollectorMap.containsKey(routeToPart)){
	        	Collection<DataEvent<JSONObject, DefaultZoieVersion>> partDataSet = DefaultStreamingIndexingManager.this._dataCollectorMap.get(routeToPart);
	        	if (partDataSet!=null){
	        	  partDataSet.add(dataEvt);
	        	}	        	
	          }
	        }
	      }
	      catch(Exception e){
	    	throw new ZoieException(e.getMessage(),e);
	      }
	      
	      Iterator<Integer> it = DefaultStreamingIndexingManager.this._zoieSystemMap.keySet().iterator();
	      while(it.hasNext()){
	        int part_num = it.next();
	        DefaultStreamingIndexingManager.this._zoieSystemMap.get(part_num).consume(DefaultStreamingIndexingManager.this._dataCollectorMap.get(part_num));
	        Zoie<BoboIndexReader,JSONObject,DefaultZoieVersion> dataConsumer = DefaultStreamingIndexingManager.this._zoieSystemMap.get(part_num);
	        if (dataConsumer!=null){
	          Collection<DataEvent<JSONObject, DefaultZoieVersion>> partDataSet =DefaultStreamingIndexingManager.this._dataCollectorMap.get(part_num);
	          if (partDataSet!=null){
	        	dataConsumer.consume(partDataSet);
	        	
	          }
	        }
	        DefaultStreamingIndexingManager.this._dataCollectorMap.put(part_num, new LinkedList<DataEvent<JSONObject, DefaultZoieVersion>>());
	      }
	      
	    }
	    
	    @Override
	    public DefaultZoieVersion getVersion()
	    {
	      return _currentVersion;
	    }
	  }

}
