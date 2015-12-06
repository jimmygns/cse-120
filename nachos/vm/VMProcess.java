package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		super.saveState();

		TranslationEntry entry;

		for (int i = 0; i < Machine.processor().getTLBSize(); ++i) {
			entry = Machine.processor().readTLBEntry(i);

			if (entry.valid) {
				pageTable[entry.vpn] = entry;
				entry.valid = false;
				Machine.processor().writeTLBEntry(i, entry);
			}
		}
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() {
		UserKernel.memoryLock.acquire();

		if (numPages > UserKernel.freePages.size()) {
			UserKernel.memoryLock.release();
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		pageTable = new TranslationEntry[numPages];

		// Give the process the amount of pages it needs
		for (int i = 0; i < numPages; ++i) {
			pageTable[i] = new TranslationEntry(i, -1, false, false, false, false);
		}
		UserKernel.memoryLock.acquire();

		// TODO Lazy-loading

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionTLBMiss:
			handleTLBMiss();
			break;
		default:
			super.handleException(cause);
			break;
		}
	}

	private void handleTLBMiss() {
		Processor processor = Machine.processor();
		int tvpn = Processor.pageFromAddress(processor.readRegister(Processor.regBadVAddr));

		TranslationEntry entry = pageTable[tvpn];

		// Invalid vpn to ppn mapping (page fault)
		if (!entry.valid) {
			entry = handlePageFault(entry, tvpn);
		}

		// Allocate TLB Entry
		VMKernel.tlbLock.acquire();
		int tlbSize = processor.getTLBSize();
		int tlbIndex = tlbSize;

		// Try to find an invalid entry
		for (int i = 0; i < tlbSize; ++i) {
			if (!processor.readTLBEntry(i).valid) {
				tlbIndex = i;
				break;
			}
		}

		// If all entries were valid, randomly evict one
		if (tlbIndex == tlbSize) {
			tlbIndex = Lib.random(tlbSize);
			TranslationEntry victim = processor.readTLBEntry(tlbIndex);
			victim.valid = false;

			// Sync victim entry back to TLB
			processor.writeTLBEntry(tlbIndex, victim);

			// Sync victim entry with page table
			// TODO Check this part when sober
			int mvpn = VMKernel.ipt[victim.ppn].entry.vpn;
			pageTable[mvpn] = new TranslationEntry(victim);
			pageTable[mvpn].vpn = pageTable[victim.vpn].vpn;

			// Update IPT
			victim.vpn = mvpn;
			VMKernel.ipt[victim.ppn].entry = victim;
		}

		// Update TLB Entry
		entry = new TranslationEntry(tvpn, entry.ppn, entry.valid, entry.readOnly, entry.used, entry.dirty);
		Machine.processor().writeTLBEntry(tlbIndex, entry);

		VMKernel.tlbLock.release();
	}

	private TranslationEntry handlePageFault(TranslationEntry entry, int vpn) {
		Processor processor = Machine.processor();

		// Allocate a physical page
		UserKernel.memoryLock.acquire();
		if (UserKernel.freePages.isEmpty()) {
			TranslationEntry victim, toEvict;

			// Sync TLB Entries


			// TODO Select a victim to evict (Clock Replacement algorithm)
			/*
			while (frames[victim].used == true) {
				frames[victim].useBit = 0;
				victim = (victim + 1) % NUMBER_OF_FRAMES;
			}

			toEvict = victim;
			victim = (victim + 1) % NUMBER_OF_FRAMES;
			*/

			if (victim.dirty) {
				//swap out;
			}

			//Invalidate PTE and TLB entry of the victim page

		} else {
			entry.ppn = UserKernel.freePages.pop();
		}
		UserKernel.memoryLock.release();

		// Dirty - swap in
		if (entry.dirty) {
		} else {

		}

		// Update page table and IPT

		return entry;
	}

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
