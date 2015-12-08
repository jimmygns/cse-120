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
		
		
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
		freeSwapPages=new LinkedList<Integer>();
		victim=0;
		numOfPinnedPages=0;
		ipt = new PageFrame[Machine.processor().getNumPhysPages()];
		for (int i = 0; i < ipt.length; ++i) {
			ipt[i] = new PageFrame(-1,new TranslationEntry(-1, -1, false, false,
					false, false));
		}
		
		iptLock = new Lock();
		swapLock = new Lock();
		victimLock = new Lock();
		pinLock = new Lock();

		swap = ThreadedKernel.fileSystem.open("swap", true);
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
		numOfPinnedPages++;
		++(ipt[ppn].pinCount);
		iptLock.release();
	}

	public static void unpinPage(int ppn) {
		iptLock.acquire();
		numOfPinnedPages--;
		--(ipt[ppn].pinCount);
		iptLock.release();
	}

	//public static TranslationEntry[] TLBTable;

	public class PageFrame {
		
		public int pid;
		public TranslationEntry entry;
		public int pinCount;
		
		
		public PageFrame(int pid, TranslationEntry entry){
			
			this.pid = pid;
			this.entry=entry;
			this.pinCount=0;
		}
		
		public void modifyPageFrame(int pid,TranslationEntry entry){
			
			this.pid = pid;
			this.entry=entry;
			this.pinCount=0;
		}
	}

	// Inverted Page Table
	public static PageFrame[] ipt;

	public static int numOfPinnedPages;
	
	public static Lock iptLock, swapLock, victimLock, pinLock;

	public static OpenFile swap;
	
	public static int victim;
	
	public static LinkedList<Integer> freeSwapPages;

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';
}
