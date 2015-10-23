package nachos.threads;

import java.util.LinkedList;

import nachos.machine.*;

/**
 * An implementation of condition variables that disables interrupt()s for
 * synchronization.
 * 
 * <p>
 * You must implement this.
 * 
 * @see nachos.threads.Condition
 */
public class Condition2 {
	/**
	 * Allocate a new condition variable.
	 * 
	 * @param conditionLock the lock associated with this condition variable.
	 * The current thread must hold this lock whenever it uses <tt>sleep()</tt>,
	 * <tt>wake()</tt>, or <tt>wakeAll()</tt>.
	 */
	public Condition2(Lock conditionLock) {
		this.conditionLock = conditionLock;
		this.waitingList = new LinkedList<KThread>();
	}

	/**
	 * Atomically release the associated lock and go to sleep on this condition
	 * variable until another thread wakes it using <tt>wake()</tt>. The current
	 * thread must hold the associated lock. The thread will automatically
	 * reacquire the lock before <tt>sleep()</tt> returns.
	 */
	public void sleep() {
		boolean intStatus = Machine.interrupt().disable();

		Lib.assertTrue(conditionLock.isHeldByCurrentThread());
		
		conditionLock.release();
		waitingList.add(KThread.currentThread());
		KThread.sleep();
		conditionLock.acquire();

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Wake up at most one thread sleeping on this condition variable. The
	 * current thread must hold the associated lock.
	 */
	public void wake() {
		boolean intStatus = Machine.interrupt().disable();

		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		if (!waitingList.isEmpty())
			waitingList.poll().ready();

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Wake up all threads sleeping on this condition variable. The current
	 * thread must hold the associated lock.
	 */
	public void wakeAll() {
		boolean intStatus = Machine.interrupt().disable();

		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		while (!waitingList.isEmpty())
			this.wake();

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Place this function inside Condition2. And make sure Condition2.selfTest() is called inside
	 * ThreadedKernel.selfTest() method. You should get exact same behaviour with Condition empty and Condition2 empty.
	 */
	public static void selfTest(){
		final Lock lock = new Lock();
		final Condition2 empty = new Condition2(lock);
		final LinkedList<Integer> list = new LinkedList<>();
		final int size = 100;

		KThread consumer = new KThread( new Runnable () {
			public void run() {
				lock.acquire();
				while (list.isEmpty())
					empty.sleep();
				Lib.assertTrue(list.size() == size, "List should have " + size + " values.");
				while(!list.isEmpty())
					System.out.println("Removed " + list.removeFirst());
				lock.release();
			}
		});

		KThread producer = new KThread( new Runnable () {
			public void run() {
				lock.acquire();
				for (int i = 0; i < size; i++) {
					list.add(i);
					System.out.println("Added " + i);
				}
				empty.wake();
				lock.release();
			}
		});

		consumer.setName("Consumer");
		producer.setName("Producer");
		consumer.fork();
		producer.fork();
		consumer.join();
		producer.join();
	}

	private Lock conditionLock;
	private LinkedList<KThread> waitingList;
}
