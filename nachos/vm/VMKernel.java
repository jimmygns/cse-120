package nachos.vm;

import java.util.LinkedList;

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
		freeSwapPages=new LinkedList<Integer>();
		victim=0;
		ipt = new PageFrame[Machine.processor().getNumPhysPages()];
		for (int i = 0; i < ipt.length; ++i) {
			ipt[i] = new PageFrame(-1,null);
		}

		tlbLock  = new Lock();
		swapLock = new Lock();
		victimLock = new Lock();

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
		public boolean used;
		public int pid;
		public TranslationEntry entry;
		public int pinCount;
		
		
		public PageFrame(int pid, TranslationEntry entry){
			this.used=true;
			this.pid = pid;
			this.entry=entry;
			this.pinCount=0;
		}
		
		public void modifyPageFrame(int pid,TranslationEntry entry){
			this.used=true;
			this.pid = pid;
			this.entry=entry;
			this.pinCount=0;
		}
	}

	// Inverted Page Table
	public static PageFrame[] ipt;

	public static Lock tlbLock, iptLock, swapLock, victimLock;

	public static OpenFile swap;
	
	public static int victim;
	
	public static LinkedList<Integer> freeSwapPages;

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';
}
