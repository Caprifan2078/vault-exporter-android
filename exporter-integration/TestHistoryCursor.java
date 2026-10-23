import org.vault.exporter.HistoryCursor;
import java.util.*;
public class TestHistoryCursor {
 static void check(boolean v,String reason){if(!v)throw new AssertionError(reason);}
 public static void main(String[] args)throws Exception {
  long[] ids=new long[10_000];
  for(int i=0;i<ids.length;i++)ids[i]=900000L-23L*i;
  Set<Long> exported=new HashSet<>();long cursor=0;int pages=0;
  while(true){
   if(cursor==ids[ids.length-1])break;
   int from=0;
   if(cursor>0)while(from<ids.length&&ids[from]>cursor)from++;
   check(from<ids.length,"no advance");
   long[] reply=Arrays.copyOfRange(ids,from,Math.min(ids.length,from+2+(pages%17)));
   HistoryCursor.Selection selected=HistoryCursor.select(cursor,reply);
   check(!selected.stalled,"unexpected stall");
   for(int i:selected.indices)check(exported.add(reply[i]),"duplicate message");
   cursor=selected.nextCursor;
   check(++pages<15_000,"pagination loop");
  }
  check(exported.size()==ids.length,"missing "+(ids.length-exported.size()));
  check(HistoryCursor.select(100,new long[]{100}).stalled,"stalled-page detection");
  check(HistoryCursor.select(100,new long[]{}).emptyResponse,"empty-page detection");
  for(long[] invalid:new long[][]{{9,10},{9,9},{0},{5,-1}}){
   boolean rejected=false;
   try{HistoryCursor.select(0,invalid);}catch(java.io.IOException expected){rejected=true;}
   check(rejected,"invalid TDLib response accepted");
  }
  System.out.println("PASS: 10000 sparse IDs, "+pages+" short pages; no omissions/duplicates, invalid pages fail closed");
 }
}