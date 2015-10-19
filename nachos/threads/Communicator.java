package nachos.threads;

import java.util.ArrayList;

import nachos.machine.*;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>, and multiple
 * threads can be waiting to <i>listen</i>. But there should never be a time
 * when both a speaker and a listener are waiting, because the two threads can
 * be paired off at this point.
 */
public class Communicator {
	/**
	 * Allocate a new communicator.
	 */
	private final int CANSEND=0;
	private final int CANRECV=1;
	private final int CANLEAVE=-1;
	
	private Lock lock;
	private Condition2 canSend, canRecv, canLeave;
	private ArrayList<Integer> buffer;
	//0 = canSend, 1 = canRecv, -1 = canLeave
	private int cond;
	
	
	public Communicator() {
		buffer = new ArrayList<Integer>();
		lock = new Lock();
		canSend = new Condition2(lock);
		canRecv = new Condition2(lock);
		canLeave = new Condition2(lock);
		//cond = CANSEND;
		
	}

	/**
	 * Wait for a thread to listen through this communicator, and then transfer
	 * <i>word</i> to the listener.
	 * 
	 * <p>
	 * Does not return until this thread is paired up with a listening thread.
	 * Exactly one listener should receive <i>word</i>.
	 * 
	 * @param word the integer to transfer.
	 */
	public void speak(int word) {
		lock.acquire();
		//while(cond!=CANSEND)
		while(buffer.size()>0)
			canSend.sleep();
		
		buffer.add(word);
		//cond = CANRECV;
		canRecv.wake();
		
		//cond = CANSEND;
		lock.release();
		
		
		
		
	}

	/**
	 * Wait for a thread to speak through this communicator, and then return the
	 * <i>word</i> that thread passed to <tt>speak()</tt>.
	 * 
	 * @return the integer transferred.
	 */
	public int listen() {
		lock.acquire();
		while(buffer.size()<1)
			canRecv.sleep();
		int w = buffer.remove(0);
		canSend.wake();
		
		//cond = CANLEAVE;
		lock.release();
		return w;
		
	}
	
	// Place this function inside Communicator. And make sure Communicator.selfTest() is called inside ThreadedKernel.selfTest() method.

	public static void selfTest(){
	    final Communicator com = new Communicator();
	    final long times[] = new long[4];
	    final int words[] = new int[2];
	    KThread speaker1 = new KThread( new Runnable () {
	        public void run() {
	            com.speak(4);
	            times[0] = Machine.timer().getTime();
	        }
	    });
	    speaker1.setName("S1");
	    KThread speaker2 = new KThread( new Runnable () {
	        public void run() {
	            com.speak(7);
	            times[1] = Machine.timer().getTime();
	        }
	    });
	    speaker2.setName("S2");
	    KThread listener1 = new KThread( new Runnable () {
	        public void run() {
	            words[0] = com.listen();
	            times[2] = Machine.timer().getTime();
	        }
	    });
	    listener1.setName("L1");
	    KThread listener2 = new KThread( new Runnable () {
	        public void run() {
	            words[1] = com.listen();
	            times[3] = Machine.timer().getTime();
	        }
	    });
	    listener2.setName("L2");
	    
	    speaker1.fork(); speaker2.fork(); listener1.fork(); listener2.fork();
	    speaker1.join(); speaker2.join(); listener1.join(); listener2.join();
	    
	    Lib.assertTrue(words[0] == 4, "Didn't listen back spoken word."); 
	    Lib.assertTrue(words[1] == 7, "Didn't listen back spoken word.");
	    Lib.assertTrue(times[0] < times[2], "speak returned before listen.");
	    Lib.assertTrue(times[1] < times[3], "speak returned before listen.");
	}
}
