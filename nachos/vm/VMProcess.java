package nachos.vm;

import java.util.HashMap;
import java.util.LinkedList;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import nachos.vm.VMKernel.PageFrame;

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
				syncTLBEntry(entry);

				// Invalidate the entry
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
		pageTable = new TranslationEntry[numPages];

		// Give the process the amount of pages it needs
		for (int i = 0; i < numPages; ++i) {
			pageTable[i] = new TranslationEntry(i, -1, false, false, false, false);
		}

		// Map VPN to CoffSection
		int readOnlySections = 0;
		int totalSections = 0;
		for (int i = 0; i < coff.getNumSections(); ++i) {
			CoffSection section = coff.getSection(i);
			for (int j = 0; j < section.getLength(); ++j) {
				totalSections++;
				int vpn = section.getFirstVPN() + j;
				pageTable[vpn].readOnly = section.isReadOnly();
				if(pageTable[vpn].readOnly)
					readOnlySections++;
				vpnCoffMap.put(vpn, section);
			}
		}
		System.out.println("COFF sections: " + totalSections);
		System.out.println("Readonly COFF Sections: " + readOnlySections);
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		// Set process for all pages in ipt to null
//		VMKernel.iptLock.acquire();   TODO release physical pages
//		// Go through every physical page, and every page owned by this process, set to null
//		for(int i=0;i<VMKernel.ipt.length;i++) {
//			PageFrame pf = VMKernel.ipt[i];
//			if(pf.process==this) {
//				pf.process = null;
//			}
//		}
//		VMKernel.iptLock.release();
		super.unloadSections();
	}

	public static int counter = 0;
	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exception</tt> constants.
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
		TranslationEntry entry;
		int index = -1;

		// Looking for an invalid entry
		for (int i = 0; i < Machine.processor().getTLBSize(); ++i) {
			entry = Machine.processor().readTLBEntry(i);
			if (entry.valid == false){
				index = i;
				break;
			}
		}

		// No invalid entries so only process entries right now, randomly evict one, and sync to the page table
		if (index == -1) {
			// Get random index, and make that the victim
			index = Lib.random(Machine.processor().getTLBSize());
			TranslationEntry victim = Machine.processor().readTLBEntry(index);

			// Sync IPT and Page Table
			VMKernel.iptLock.acquire();
			syncTLBEntry(victim);
			VMKernel.iptLock.release();

			// Invalidate the entry
			victim.valid = false;
			Machine.processor().writeTLBEntry(index, victim);
		}

		// Find the PTE of the TLB Miss
		int missVAddress = Machine.processor().readRegister(Processor.regBadVAddr);
		int missVPN = Processor.pageFromAddress(missVAddress);
		TranslationEntry tlbPTE = pageTable[missVPN];
		Machine.processor().writeTLBEntry(index, tlbPTE.valid ? tlbPTE : handlePageFault(tlbPTE));
	}
	
	/**
	 *  
	 * @param entry -- Reference to missed PTE
	 * @param vpn -- VPN of what we were trying to access
	 * @return
	 */
	private TranslationEntry handlePageFault(TranslationEntry entry) {
		TranslationEntry tlbEntry;
		int vpn = entry.vpn;
		int freePageIndex = -1;
		int newPPN;

		// Go through all entries of the ipt and try to look for a null val
		VMKernel.iptLock.acquire();
		for (int i = 0; i < VMKernel.ipt.length; ++i) {
			if (VMKernel.ipt[i].entry == null) {
				freePageIndex = i;
				break;
			}
		}

		if (freePageIndex != -1) {
			tlbEntry = new TranslationEntry(vpn, freePageIndex, true, false, false, false);
			VMKernel.iptLock.release();
		} else {
			//No free memory, need to evict a page
			VMKernel.iptLock.release();

		    // Sync TLB entries
			for(int i = 0; i < Machine.processor().getTLBSize(); ++i){
				TranslationEntry syncEntry = Machine.processor().readTLBEntry(i);

				if(syncEntry.valid){
					// Sync IPT and Page Table
					VMKernel.iptLock.acquire();
					syncTLBEntry(syncEntry);
					VMKernel.iptLock.release();

					// Invalidate the entry
					syncEntry.valid = false;
					Machine.processor().writeTLBEntry(i, syncEntry);
				}
			}

			// Select a victim for replacement - Clock algorithm
			TranslationEntry toEvict;
			while (true) {
				// TODO pinPage edge case
				if (VMKernel.ipt[VMKernel.victim].pinCount == 0) { // Check for null defeats nullptr
					// Check for null defeats nullptr
					if (VMKernel.ipt[VMKernel.victim].entry.used == true) {
						VMKernel.ipt[VMKernel.victim].entry.used = false;
						VMKernel.victimLock.acquire();
						VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;

						// Make sure victim value does not exceed max
						if (VMKernel.victim == Integer.MAX_VALUE) {
							VMKernel.victim = 0;
						}

						VMKernel.victimLock.release();
					} else {
						// Still increment clock on page replacement
						VMKernel.victimLock.acquire();
						VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;

						// Make sure victim value does not exceed max
						if (VMKernel.victim == Integer.MAX_VALUE) {
							VMKernel.victim = 0;
						}

						VMKernel.victimLock.release();
						break;
					}
				} else {
					VMKernel.victimLock.acquire();
					VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;

					// Make sure victim value does not exceed max
					if (VMKernel.victim == Integer.MAX_VALUE) {
						VMKernel.victim = 0;
					}

					VMKernel.victimLock.release();
				}
			}

			toEvict = VMKernel.ipt[VMKernel.victim].entry;      // Ref to Victim in the physical page table entry
			newPPN = toEvict.ppn;
			tlbEntry = new TranslationEntry(vpn, toEvict.ppn, true, false, false, false);

			System.out.println("Evict Page: " + toEvict.ppn);
			// Handle swap out if necessary
			if (toEvict.dirty) {       // Only write to disk if the page is dirty
				// Search for free swap page, create a new one if you can't find one
				VMKernel.swapLock.acquire();
				int spn=-1;
				for(int i =0;i<VMKernel.freeSwapPages.size();i++){
					if(VMKernel.freeSwapPages.get(i)==null){
						spn=i;
						VMKernel.freeSwapPages.set(spn, spn);
						break;
					}
				}
				if(spn == -1) {                    // Cannot find free, grow the list
					System.out.println("Appending swap pages, no free space");
					spn = VMKernel.freeSwapPages.size();
					VMKernel.freeSwapPages.add(spn);
				}
				byte[] memory = Machine.processor().getMemory();
				int offset = toEvict.ppn * pageSize;

				// Write from physical memory to disk
				VMKernel.swap.write(spn * pageSize, memory, offset, pageSize);
				VMKernel.swapLock.release();

				// Invalidate PTE and TLB entry of the victim page (store spn in ppn value)
				pageTable[toEvict.vpn].valid = false;
				toEvict.valid = false;
				toEvict.ppn = spn; // Store spn in ppn val

				for(int i = 0; i < Machine.processor().getTLBSize(); ++i){
					TranslationEntry e = Machine.processor().readTLBEntry(i);

					if(e.valid && e.vpn == toEvict.vpn) {
						Machine.processor().writeTLBEntry(i, toEvict);
						break;
					}
				}
			}
		}

		// Check if the TLB TranslationEntry is dirty - If so, Swap In, already checks for
		if (entry.ppn == -1) {
			// Check vpn belongs to a CoffSection
			if(vpn < vpnCoffMap.size()) {
				CoffSection section = vpnCoffMap.get(vpn);
				section.loadPage(vpn - section.getFirstVPN(), tlbEntry.ppn);
			} else {
				// Not a COFF section - zero out page
				byte[] memory = Machine.processor().getMemory();
				for (int i = 0; i < pageSize; ++i) {
					memory[tlbEntry.ppn * pageSize + i] = 0;
				}

				entry.valid = false;
				entry.dirty = true;
			}
		} else {
			// Second/later miss
			if (entry.readOnly) {
				// Read from COFF section if read-only
				CoffSection section = vpnCoffMap.get(vpn);
				section.loadPage(vpn - section.getFirstVPN(), tlbEntry.ppn);
			} else {
				// Read from swap if dirty
				byte[] memory = Machine.processor().getMemory();
				VMKernel.freeSwapPages.set(entry.ppn, null);
				VMKernel.swap.read(entry.ppn * pageSize, memory, tlbEntry.ppn * pageSize, pageSize);
				entry.dirty = false;   // No longer dirty since paging in
			}
		}

		VMKernel.iptLock.acquire();
		syncTLBEntry(tlbEntry);
		VMKernel.iptLock.release();

		return tlbEntry;
	}

	/*
	 * Sync IPT and Page Table from TLB Entry
	 */
	public void syncTLBEntry(TranslationEntry tlbEntry) {
		TranslationEntry des = pageTable[tlbEntry.vpn];

		// Sync Page Table with TLB entry
		des.vpn = tlbEntry.vpn;
		des.ppn = tlbEntry.ppn;
		des.valid = tlbEntry.valid;
		des.readOnly = tlbEntry.readOnly;
		des.used = tlbEntry.used;
		des.dirty = tlbEntry.dirty;

		// Sync IPT entry with Page Table
		VMKernel.ipt[tlbEntry.ppn].entry = pageTable[tlbEntry.vpn];
	}

	private HashMap<Integer, CoffSection> vpnCoffMap = new HashMap<>();

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
