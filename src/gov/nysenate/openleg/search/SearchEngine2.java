package gov.nysenate.openleg.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.StringTokenizer;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryParser.ParseException;

import util.JsonSerializer;
import util.XmlSerializer;

import gov.nysenate.openleg.lucene.LuceneResult;
import gov.nysenate.openleg.lucene.LuceneSerializer;

public class SearchEngine2 extends SearchEngine {

	private static SearchEngine2 _instance = null;
	
	public static void main(String[] args) throws Exception {
		SearchEngine2 engine = SearchEngine2.getInstance();
		
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		
		String line = null;
		System.out.print("openlegLuceneConsole> ");
		while (!(line = reader.readLine()).equals("quit"))
		{
//			if(line.startsWith("bill ")) {
//				String cmd = line.substring(line.indexOf(" ")+1);
//				StringTokenizer st = new StringTokenizer(cmd);
//				Bill bill = PMF.getDetachedBill(st.nextToken(),new Integer(st.nextToken()));
//				
//				if(bill != null) {
//					engine.indexSenateObjects(new ArrayList(Arrays.asList(bill)), new LuceneSerializer[]{new XmlSerializer(), new JsonSerializer()});
//				}
//				else {
//					System.out.println("bill was null");
//				}
//			}
//			if(line.startsWith("meeting ")) {
//				String cmd = line.substring(line.indexOf(" ")+1);
//				StringTokenizer st = new StringTokenizer(cmd);
//				
//				Meeting meeting = (Meeting)PMF.getDetachedObject(Meeting.class, "id", st.nextToken(), null);
//				
//				if(meeting != null) {
//					engine.indexSenateObjects(new ArrayList(Arrays.asList(meeting)), new LuceneSerializer[]{new XmlSerializer(), new JsonSerializer()});
//				}
//				else {
//					System.out.println("no meeting!");
//				}
//				
//			}
			if (line.startsWith("index "))
			{
				String cmd = line.substring(line.indexOf(" ")+1);
				StringTokenizer st = new StringTokenizer(cmd);
				String iType = st.nextToken();
				int start = 1;
				int max = 1000000;
				int pageSize = 10;
				
				if (st.hasMoreTokens())
					start = Integer.parseInt(st.nextToken());
				
				if (st.hasMoreTokens())
					max = Integer.parseInt(st.nextToken());
				
				if (st.hasMoreTokens())
					pageSize = Integer.parseInt(st.nextToken());
				
				engine.indexSenateData(iType, start, max, pageSize,
						new LuceneSerializer[]{new XmlSerializer(), new JsonSerializer()});
			}
			else if (line.startsWith("optimize"))
				engine.optimize();
			else if (line.startsWith("delete"))
			{
				StringTokenizer cmd = new StringTokenizer(line.substring(line.indexOf(" ")+1)," ");
				String type = cmd.nextToken();
				String id = cmd.nextToken();
				engine.deleteSenateObjectById(type, id);
			}
			else if (line.startsWith("create"))
				engine.createIndex();
			else {
				SenateResponse sr = engine.search(line, "xml", 1, 10, null, false);
				if(sr != null && !sr.getResults().isEmpty()) {
					for(Result r:sr.getResults()) {
						System.out.println(r.getOid());
					}
				}
			}
			
			System.out.print("openleg search > ");
		}
		System.out.println("Exiting Search Engine");
	}

	private SearchEngine2() {

//		super("/usr/local/openleg/lucene/2");
		super("/Users/jaredwilliams/Documents/lucene/2");
		logger = Logger.getLogger(SearchEngine2.class);
	}
	
	public static synchronized SearchEngine2 getInstance ()
	{
		
		if (_instance == null)
		{
			_instance = new SearchEngine2();
		}
		
		return _instance;
		
	}
	
	
	public SenateResponse get(String format, String otype, String oid, String sortField, int start, int numberOfResults, boolean reverseSort) {
		
    	SenateResponse response = null;
    			    	
		try {
			
			String query = null;
			if (otype != null && oid !=null)
				query = "otype:"+otype+" AND oid:"+oid;
			else if (otype !=null && oid == null)
				query = "otype:"+otype;
			else if (otype ==null && oid != null)
				query = "oid:"+oid;
			else
				logger.error("Get Request had neither otype nor oid specified");
			
			if (query != null)
				response = search( query, format, start, numberOfResults, sortField, reverseSort);
			
		} catch (IOException e) {
			logger.warn(e);
		} catch (ParseException e) {
			logger.warn(e);		}
		
		return response;
    	
    }
	
	public SenateResponse search(String searchText, String format, int start, int max, String sortField, boolean reverseSort) throws ParseException, IOException {
		
    	String data = "o"+format.toLowerCase()+"";
    	
    	LuceneResult result = search(searchText,start,max,sortField,reverseSort);
    	
    	SenateResponse response = new SenateResponse();
    	
    	if (result == null)
    	{
    		response.addMetadataByKey("totalresults", 0 );
    	}
    	else
    	{
    		
    	
	    	response.addMetadataByKey("totalresults", result.total );
	    	
	    	for (Document doc : result.results) {
	    		
	    		String lastModified = doc.get("modified");
	    		if (lastModified == null || lastModified.length() == 0)
	    			lastModified = new Date().getTime()+"";
	    		
	    		response.addResult(new Result(doc.get("otype"),doc.get(data), doc.get("oid"), Long.parseLong(lastModified)));
	    	}
    	}
	    	
    	return response;
	}
}