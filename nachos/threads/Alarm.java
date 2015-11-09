package nachos.threads;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Random;

import nachos.machine.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() {
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		// Remove interrupt disabling later if not needed
		boolean intStatus = Machine.interrupt().disable();

		while(!waitQueue.isEmpty() && waitQueue.peek().getWaitTime() <= Machine.timer().getTime())
			waitQueue.remove().ready();

		Machine.interrupt().restore(intStatus);

		KThread.currentThread().yield();
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		boolean intStatus = Machine.interrupt().disable();

		long wakeTime = Machine.timer().getTime() + x;
		KThread.currentThread().setWaitTime(wakeTime);
		waitQueue.add(KThread.currentThread());
		KThread.sleep();

		Machine.interrupt().restore(intStatus);
	}
	
	/**
     * Place this function inside Alarm. And make sure Alarm.selfTest() is called inside ThreadedKernel.selfTest()
	 * method.
	 */
	public static void selftest() {
		final LinkedList<KThread> threads = new LinkedList<KThread>();
		final int size = 100;
		final int max = 5;
		final Random rand = new Random();

		for (int i = 0; i < size; ++i) {
			KThread thread = new KThread(new Runnable() {
				public void run() {
					int waitTime = 10000 * rand.nextInt(max + 1);
					long time = Machine.timer().getTime();
					String name = "Thread " + ++dSize;

					System.out.println(name + " calling wait at time:" + time);
					ThreadedKernel.alarm.waitUntil(waitTime);
					System.out.println(name + " woken up after:" + (Machine.timer().getTime() - time));
					Lib.assertTrue((Machine.timer().getTime() - time) > waitTime, " thread woke up too early.");
				}
			});

			thread.setName("Thread " + dSize);
			threads.add(thread);
		}

		for (KThread t:threads) {
			t.fork();
			t.join();
		}
	}

	static class PQKWaitSort implements Comparator<KThread> {
		public int compare(KThread one, KThread two) {
			return (int)(two.getWaitTime() - one.getWaitTime());
		}
	}

	// Only used in selfTest()
	private static int dSize = 0;

	private PQKWaitSort comp = new PQKWaitSort();

	private PriorityQueue<KThread> waitQueue = new PriorityQueue<KThread>(comp);
}