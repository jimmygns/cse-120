package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() {
		super();
		//TLBTable = new TranslationEntry[Machine.processor().getTLBSize()];

		ipt = new PageFrame[Machine.processor().getNumPhysPages()];
		for (int i = 0; i < ipt.length; ++i) {
			ipt[i] = new PageFrame();
		}

		tlbLock  = new Lock();
		swapLock = new Lock();

		swap = ThreadedKernel.fileSystem.open("swap", true);
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() {
		super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run() {
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
		swap.close();
		ThreadedKernel.fileSystem.remove("swap");
	}

	public static void pinPage(int ppn) {
		iptLock.acquire();
		++(ipt[ppn].pinCount);
		iptLock.release();
	}

	public static void unpinPage(int ppn) {
		iptLock.acquire();
		--(ipt[ppn].pinCount);
		iptLock.release();
	}

	//public static TranslationEntry[] TLBTable;

	public class PageFrame {
		public int pid;
		public TranslationEntry entry;
		public int pinCount;
	}

	// Inverted Page Table
	public static PageFrame[] ipt;

	public static Lock tlbLock, iptLock, swapLock;

	public static OpenFile swap;

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';
}
