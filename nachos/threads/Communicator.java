package nachos.threads;

import java.util.LinkedList;

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
	public Communicator() {
		lock = new Lock();
		canSend = new Condition2(lock);
		canRecv = new Condition2(lock);
		canLeave = new Condition2(lock);
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
		
		while (buffer != null)
			canSend.sleep();
		
		buffer = new Integer(word);

		canRecv.wake();
		canLeave.sleep();
		
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

		while (buffer == null)
			canRecv.sleep();

		int w = buffer.intValue();
		buffer = null;

		canLeave.wake();
		canSend.wake();
		
		lock.release();

		return w;
	}

	/**
	 * Place this function inside Communicator. And make sure Communicator.selfTest() is called inside
	 * ThreadedKernel.selfTest() method.
	 */
	public static void selfTest() {
	    final Communicator com = new Communicator();
		final LinkedList<KThread> speakers  = new LinkedList<KThread>();
		final LinkedList<KThread> listeners = new LinkedList<KThread>();
		final LinkedList<Integer> spokenWords = new LinkedList<Integer>();
		final int size = 10;

		for(int i = 0; i < size; ++i) {
			speakers.add(new KThread(new Runnable() {
				public void run() {
					com.speak(dSize++);
				}
			}));

			listeners.add(new KThread(new Runnable() {
				public void run() {
					int val = com.listen();
					System.out.println(val);
					spokenWords.add(val);
				}
			}));
		}

		for(KThread t:speakers)
			t.fork();
		for(KThread t:listeners)
			t.fork();
		for(KThread t:speakers)
			t.join();
		for(KThread t:listeners)
			t.join();

		for(int i = 0; i < size; ++i) {
			int word = spokenWords.poll().intValue();
			Lib.assertTrue(i == word, "Words were not spoken correctly i: " + i + " sWords(i): " + word);
		}

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
                times[2] = Machine.timer().getTime();
                words[0] = com.listen();
            }
        });
        listener1.setName("L1");
        KThread listener2 = new KThread( new Runnable () {
            public void run() {
                times[3] = Machine.timer().getTime();
                words[1] = com.listen();
            }
        });
        listener2.setName("L2");

        speaker1.fork(); speaker2.fork(); listener1.fork(); listener2.fork();
        speaker1.join(); speaker2.join(); listener1.join(); listener2.join();

        Lib.assertTrue(words[0] == 4, "Didn't listen back spoken word.");
        Lib.assertTrue(words[1] == 7, "Didn't listen back spoken word.");
        Lib.assertTrue(times[0] > times[2], "speak() returned before listen() called.");
        Lib.assertTrue(times[1] > times[3], "speak() returned before listen() called.");
	}

	// Only used in selfTest()
	private static int dSize = 0;

	private Lock lock;

	private Condition2 canSend, canRecv, canLeave;

	private Integer buffer;
}
