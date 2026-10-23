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
  // Simulate a killed exporter: persist the last completed-page cursor and
  // a durable set of staged IDs, then resume from the same checkpoint.
  Set<Long> resumedExported=new HashSet<>();
  long savedCursor=0;
  int restarts=0;
  for(int pageIndex=0;resumedExported.size()<ids.length;pageIndex++){
    int start=0;
    if(savedCursor>0)while(start<ids.length&&ids[start]>savedCursor)start++;
    long[] answer=Arrays.copyOfRange(ids,start,Math.min(ids.length,start+2+(pageIndex%13)));
    HistoryCursor.Selection choice=HistoryCursor.select(savedCursor,answer);
    check(choice.indices.length>0,"resume stopped early");
    Set<Long> onPage=new HashSet<>();
    for(int n:choice.indices)check(onPage.add(answer[n]),"duplicate within checkpoint");
    resumedExported.addAll(onPage);
    savedCursor=choice.nextCursor; // checkpoint only AFTER this page is staged
    if(pageIndex%27==0) { restarts++; } // reopen with the last committed cursor
    check(pageIndex<15_000,"stalled resume");
  }
  check(resumedExported.size()==ids.length,"lost data after restart");
  check(resumedExported.equals(exported),"restart diverged from uninterrupted export");
  System.out.println("PASS: 10000 sparse IDs, "+pages+" short pages; no omissions/duplicates, restart checkpoints and invalid pages fail closed");
 }
}