package com.sande.mythai.response.memory;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Process-wide admission shared by dialogue (including spontaneous speech), vision and memory.
 * Existing foreground queue sizes/concurrency remain authoritative. Background HTTP cooperatively yields
 * by interruption; its permit is retained until transport unwinds (never pretend the device is already free). */
public final class ModelAdmission {
    private static int queued,active,players=-1;
    private static boolean optional;
    private static Thread backgroundOwner;
    private static boolean requiresNoPlayers;
    private ModelAdmission() {}
    public record Status(int foregroundPending,int foregroundActive,boolean optionalActive,int onlinePlayers) {}
    public static synchronized Status status(){return new Status(queued,active,optional,players);}
    public static synchronized void players(int count){players=count;if(count!=0&&requiresNoPlayers)interruptBackground();ModelAdmission.class.notifyAll();}
    public static synchronized Ticket foreground(){queued++;interruptBackground();return new Ticket();}
    private static void interruptBackground(){if(backgroundOwner!=null&&backgroundOwner!=Thread.currentThread())backgroundOwner.interrupt();}
    public static final class Ticket implements AutoCloseable {
        private boolean closed;
        private Ticket() {}
        @Override public void close(){synchronized(ModelAdmission.class){if(!closed){closed=true;queued--;ModelAdmission.class.notifyAll();}}}
    }
    public static <T>T run(Ticket ticket,int limit,long timeoutMillis,Supplier<T> task) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized(ModelAdmission.class){
            try {
                while(optional||active>=Math.max(1,limit)){
                    if(ticket.closed)throw new RejectedExecutionException("cancelled model job");
                    long left=deadline-System.nanoTime();if(left<=0)throw new RejectedExecutionException("model admission timeout");
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(ModelAdmission.class,left);
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new RejectedExecutionException("interrupted model admission",e);}
            if(ticket.closed)throw new RejectedExecutionException("cancelled model job");
            active++;
        }
        try{return task.get();}finally{synchronized(ModelAdmission.class){active--;ModelAdmission.class.notifyAll();}}
    }
    /** No optional queue: a busy device immediately falls back/defer. Background also requires known zero players. */
    public static synchronized Lease optional(boolean background) {
        if(optional||queued>0||active>0||background&&players!=0)return null;
        optional=true;requiresNoPlayers=background;backgroundOwner=background?Thread.currentThread():null;return new Lease();
    }
    /** Post-dialogue social inference may run with players online, but yields to every foreground request. No queue. */
    public static synchronized Lease followup() {
        if(optional||queued>0||active>0||players<0)return null;
        optional=true;requiresNoPlayers=false;backgroundOwner=Thread.currentThread();return new Lease();
    }
    public static final class Lease implements AutoCloseable {
        private boolean closed;
        private Lease() {}
        @Override public void close(){synchronized(ModelAdmission.class){if(!closed){closed=true;optional=false;backgroundOwner=null;requiresNoPlayers=false;ModelAdmission.class.notifyAll();}}}
    }
}
