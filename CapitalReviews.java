import com.google.gson.*;
import java.io.IOException;
import java.util.regex.*;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.StringTokenizer; 
import java.util.ArrayList;
import java.util.Arrays; 
/**
 * * This Map-Reduce code will go through every Amazon review in rfox12:reviews It will then output
 * data on the top-level JSON keys
 */
public class CapitalReviews extends Configured implements Tool {
  // Just used for logging
  protected static final Logger LOG = LoggerFactory.getLogger(CapitalReviews.class);

  // This is the execution entry point for Java programs
  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(HBaseConfiguration.create(), new CapitalReviews(), args);
    System.exit(res);
  }

  public int run(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("Need 1 argument (hdfs output path), got: " + args.length);
      return -1;
    }

    // Now we create and configure a map-reduce "job"
    Job job = Job.getInstance(getConf(), "CapitalReviews");
    job.setJarByClass(CapitalReviews.class);

    // By default we are going to can every row in the table
    Scan scan = new Scan();
    scan.setCaching(500); // 1 is the default in Scan, which will be bad for MapReduce jobs
    scan.setCacheBlocks(false); // don't set to true for MR jobs

    // This helper will configure how table data feeds into the "map" method
    TableMapReduceUtil.initTableMapperJob(
        "rfox12:reviews", // input HBase table name
        scan, // Scan instance to control CF and attribute selection
        MapReduceMapper.class, // Mapper class
        Text.class, // Mapper output key
        IntWritable.class, // Mapper output value
        job, // This job
        true // Add dependency jars (keep this to true)
        );

    // Specifies the reducer class to used to execute the "reduce" method after "map"
    job.setReducerClass(MapReduceReducer.class);

    // For file output (text -> number)
    FileOutputFormat.setOutputPath(
        job, new Path(args[0])); // The first argument must be an output path
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(IntWritable.class);

    // What for the job to complete and exit with appropriate exit code
    return job.waitForCompletion(true) ? 0 : 1;
  }

  public static class MapReduceMapper extends TableMapper<Text, IntWritable> {
    private static final Logger LOG = LoggerFactory.getLogger(MapReduceMapper.class);

    // Here are some static (hard coded) variables
    private static final byte[] CF_NAME = Bytes.toBytes("cf"); // the "column family" name
    private static final byte[] QUALIFIER = Bytes.toBytes("review_data"); // the column name
    private static final IntWritable one =
        new IntWritable(1); // a representation of "1" which we use frequently

    private Counter rowsProcessed; // This will count number of rows processed
    private JsonParser parser; // This gson parser will help us parse JSON

    // This setup method is called once before the task is started
    @Override
    protected void setup(Context context) {
      parser = new JsonParser();
      rowsProcessed = context.getCounter("CapitalReviews", "Rows Processed");
    }

    // This "map" method is called with every row scanned.
    @Override
    public void map(ImmutableBytesWritable rowKey, Result value, Context context)
        throws InterruptedException, IOException {
      try {
        // Here we get the json data (stored as a string) from the appropriate column
        String jsonString = new String(value.getValue(CF_NAME, QUALIFIER));

        // Now we parse the string into a JsonElement so we can dig into it
        JsonElement jsonTree = parser.parse(jsonString);
        JsonObject jsonObject = jsonTree.getAsJsonObject();

	// My Added code for finding the count of positive and negative reivews with capitalized words (some or all)      
        double overallrating = jsonObject.get("overall").getAsDouble();
        String review_text = jsonObject.get("reviewText").getAsString();
        StringTokenizer st = new StringTokenizer(review_text);
      
        ArrayList<String> words_cap = new ArrayList<>();
        Pattern pattern = Pattern.compile("[A-Z][A-Z]+");
        Matcher matcher = pattern.matcher(review_text);
        while (matcher.find()) {
            words_cap.add(matcher.group());
        }

            
        int count = 0;
        
        if(overallrating == 1.0  || overallrating == 2.0){
            if(words_cap.size() >= 1 && words_cap.size() < st.countTokens()){
                //System.out.println("Some Capital Word " + words_cap.size());
                context.write(new Text("Negative Reviews with some capital word: "),one);
    
            }else{
                context.write(new Text("Negative Reviews with all capital word: "),one);
            }
            
        }else if(overallrating == 4.0  || overallrating == 5.0){
            if(words_cap.size() >= 1 && words_cap.size() < st.countTokens()){
                 context.write(new Text("Positive Reviews with some capital word: "),one);
            }else{
              context.write(new Text("Positive Reviews with all capital word: "),one);
            }
        }
            
             
      

       // Here we increment a counter that we can read when the job is done
      rowsProcessed.increment(1);
      } catch (Exception e) {
        LOG.error("Error in MAP process: " + e.getMessage(), e);
      }
    }
  }

  // Reducer to simply sum up the values with the same key (text)
  // The reducer will run until all values that have the same key are combined
  public static class MapReduceReducer extends Reducer<Text, IntWritable, Text, IntWritable> {
    @Override
    public void reduce(Text key, Iterable<IntWritable> counts, Context context)
        throws IOException, InterruptedException {
      int sum = 0;
      for (IntWritable count : counts) {
        sum += count.get();
      }
      context.write(key, new IntWritable(sum));
    }
  }
}
