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
				// Synch tlb entry to page table
//				pageTable[entry.vpn] = new TranslationEntry(entry);  // Copy by value
				syncEntry(pageTable[entry.vpn],entry);
				VMKernel.iptLock.acquire();
				syncEntry(VMKernel.ipt[entry.ppn].entry,entry);
				VMKernel.iptLock.release();
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
			pageTable[i] = new TranslationEntry(i, -1, false, false, false, false);   // Assume dirty
		}

		// Map VPN to CoffSection
		for (int i = 0; i < coff.getNumSections(); ++i) {
			CoffSection section = coff.getSection(i);
			pageTable[i].dirty = false;               // Coff section readonly pages are not dirty
			for (int j = 0; j < section.getLength(); ++j) {
				int vpn = section.getFirstVPN() + j;
				pageTable[vpn].readOnly = section.isReadOnly();
				vpnCoffMap.put(vpn, section);
			}
		}

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
		TranslationEntry entry;
		int index = -1;

		// Looking for an invalid entry
		for (int i = 0; i < Machine.processor().getTLBSize(); ++i){
			entry = Machine.processor().readTLBEntry(i);
			if (entry.valid == false){
				index=i;
				break;
			}
		}
		// TODO don't evict recently evicted
		// No invalid entries so only process entries right now, randomly evict one, and sync to the page table
		if (index==-1) {
			// Get random index, and make that the victim
			do {
				index = Lib.random(Machine.processor().getTLBSize());
			}while(index==VMKernel.justWritten);  // just written makes sure dosen't write over twice
			VMKernel.justWritten = index;
			TranslationEntry victim = Machine.processor().readTLBEntry(index);
			// Sync pageTable with tlb entry
			syncEntry(pageTable[victim.vpn],victim);
//			pageTable[victim.vpn]=victim;
			//sync TLB entry with ipt before eject
			VMKernel.iptLock.acquire();
			syncEntry(VMKernel.ipt[victim.ppn].entry,victim);
//			VMKernel.ipt[victim.ppn].entry=victim;
			VMKernel.iptLock.release();
		}

		// Find the PTE of the TLB Miss
		int missVAddress = Machine.processor().readRegister(Processor.regBadVAddr);
		int missVPN = Processor.pageFromAddress(missVAddress);
		
		// Under strategy guide, says if the TLB entry is a write, synch TLB with PageTable of process after setting used and dirty to true?
		TranslationEntry tlbPTE = new TranslationEntry(pageTable[missVPN]);   // Will automatically create copy i writeTLB but w/e TODO change later to ref
		Lib.assertTrue(tlbPTE.vpn == missVPN);

		if(tlbPTE.valid) {
			Machine.processor().writeTLBEntry(index, tlbPTE);
		}
		else{
			Machine.processor().writeTLBEntry(index, handlePageFault(tlbPTE));
		}
	}
	
	
	
	private void syncTLBEntries() {
		TranslationEntry syncEntry;
		for(int i=0;i<Machine.processor().getTLBSize();i++){
			syncEntry = Machine.processor().readTLBEntry(i);
			if(syncEntry.valid){
				if(syncEntry.dirty) {
					System.out.println("DIRTY!");
				}
				// Sync ipt entry ref
				VMKernel.iptLock.acquire();
				syncEntry(VMKernel.ipt[syncEntry.ppn].entry,syncEntry);
				VMKernel.iptLock.release();
			}
		}
	}
	
	
	/**
	 *  
	 * @param entry -- PageTable Entry that we tried to find in TLB but caused miss
	 * @param vpn -- VPN of what we were trying to access
	 * @return
	 */
	private TranslationEntry handlePageFault(TranslationEntry entry) {
		
		for(int i=0;i<pageTable.length;i++){
			Lib.assertTrue(pageTable[i].vpn==i);
		}
//		privilege.stats.pageFaults++;
		TranslationEntry tlbEntry = null;
//		syncTLBEntries(); Causes it to work but shouldn't be here
		// Allocate a Physical Page
		int freePageIndex = -1;
		
		// Go through all entries of the ipt and try to look for a null val
		VMKernel.iptLock.acquire();
		for(int i=0;i<VMKernel.ipt.length;i++){
			if(VMKernel.ipt[i].entry==null) {
				freePageIndex=i;
				break;
			}
		}
		if (freePageIndex!=-1) {
			tlbEntry = new TranslationEntry(entry.vpn, freePageIndex, true, false, false, false); // Set readOnly and used bit?
			syncEntry(pageTable[entry.vpn],tlbEntry);              // set pt ref
			VMKernel.ipt[tlbEntry.ppn].entry = pageTable[entry.vpn];  // Set ipt ref
			VMKernel.iptLock.release();
		} else { //No free memory, need to evict a page
			VMKernel.iptLock.release();
		    // Sync TLB entries; 
			syncTLBEntries();
			
//		    Select a victim for replacement; //Clock algorithm
			TranslationEntry toEvict;
			VMKernel.iptLock.acquire();    // Replaced code here TODO 
			VMKernel.victimLock.acquire();
			while (true) {
//				// TODO pinPage edge case
//				if(VMKernel.numOfPinnedPages == Machine.processor().getNumPhysPages()) {
//					condition.sleep();
//				}

				if(VMKernel.ipt[VMKernel.victim].pinCount==0){ // Check for null defeats nullptr
					if(VMKernel.ipt[VMKernel.victim].entry.used==true){
						VMKernel.ipt[VMKernel.victim].entry.used=false;
//						VMKernel.victimLock.acquire();
						VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;
						if(VMKernel.victim==Integer.MAX_VALUE) {     // Make sure victim value does not exceed max
							System.out.println("int max!");
							VMKernel.victim = 0;
						}
//						VMKernel.victimLock.release();
					}
					else{
						// Still increment clock on page replacement
						VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;
						if(VMKernel.victim==Integer.MAX_VALUE) {     // Make sure victim value does not exceed max
							System.out.println("int max!");
							VMKernel.victim = 0;
						}
						break;
					}
				}
				else{
//					VMKernel.victimLock.acquire();
					VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;
					if(VMKernel.victim==Integer.MAX_VALUE) {     // Make sure victim value does not exceed max
						System.out.println("int max!");
						VMKernel.victim = 0;
					}
//					VMKernel.victimLock.release();
				}
			}
			toEvict = VMKernel.ipt[VMKernel.victim].entry;      // Ref to Victim in the physical page table entry
			VMKernel.victimLock.release();
			VMKernel.iptLock.release(); 
			
			tlbEntry = new TranslationEntry(entry.vpn, toEvict.ppn, true, false, false, false);
			// Since we now hold ref to evicted page in toEvict, we can replace the ipt entry
			VMKernel.iptLock.acquire();
			syncEntry(pageTable[entry.vpn],tlbEntry);
			VMKernel.ipt[toEvict.ppn].entry=pageTable[entry.vpn];  // Set ip ref
			VMKernel.iptLock.release();
			
			// Handle swap out if necessary
			if (toEvict.dirty) {       // Only write to disk if the page is dirty
				// Search for free swap page, create a new one if you can't find one
				System.out.println("Swap out");
				VMKernel.swapLock.acquire();
				int spn=-1;
				for(int i =0;i<VMKernel.freeSwapPages.size();i++){
					if(VMKernel.freeSwapPages.get(i)==null){
						spn=i;
						VMKernel.freeSwapPages.set(spn, spn);
						break;
					}
				}
				if(spn==-1){                    // Cannot find free, grow the list
					System.out.println("Appending swap pages, no free space");
					spn=VMKernel.freeSwapPages.size();
					VMKernel.freeSwapPages.add(spn);
				}
				byte[] memory = Machine.processor().getMemory();
				int offset = toEvict.ppn*pageSize;
				VMKernel.swap.write(spn*pageSize, memory,offset, pageSize);   // Write from phys mem to disk
				VMKernel.swapLock.release();

				//Invalidate PTE and TLB entry of the victim page
				toEvict.valid=false;
				toEvict.ppn = spn; // Store spn in ppn val
			}
			
		}

		// Now read the appropriate data into memory
		
		// TODO do we need lock for machine processor memory?
		
		
//		if (entry.ppn==-1) {
			
			
//			
//			do lazy loading (this is the second step in the strategy guide,
//		    reading from COFF or zeroing bytes in stack/arg pages)
//			} else { // second or later miss
//				if (TE is readOnly) {
//					read from COFF (this will only happen for code pages)
//				} else {
//					read from swap file
//				}
//
//			}
//		
		
		
		// Check if the TLB TranslationEntry is dirty - If so, Swap In, already checks for 
		if (entry.dirty) {                    // Is in disk
			// Read swap to get data
			System.out.println("Swap in");
			byte[] memory = Machine.processor().getMemory();
			// entry.ppn currently refers to entry's swp, tlbEntry refers to ppn
			VMKernel.freeSwapPages.set(entry.ppn,null);
			if(VMKernel.swap.read(entry.ppn*pageSize,memory, tlbEntry.ppn*pageSize, pageSize) <= 0) {
				System.out.println("Error, did not read memory"); // TODO change error
			}
			entry.dirty = false;   // No longer dirty since paging in
		} else if(entry.vpn < vpnCoffMap.size()) {     // Is coff section
			// Check vpn belongs to a CoffSection
			CoffSection section = vpnCoffMap.get(entry.vpn);
			// if coff not, set dirty bit TODO do we actually do this or possible errors?
//			if(!pageTable[entry.vpn].readOnly)
//				pageTable[entry.vpn].dirty=true;
			tlbEntry.readOnly = pageTable[entry.vpn].readOnly;
			section.loadPage(entry.vpn - section.getFirstVPN(), tlbEntry.ppn);  // SPN is vpn - section.get
		} else {  // Not a COFF section, zero out stack page
			// Get memory, for that section of memory (bytes from start index to startIndex + page size), 0
	    	byte[] memory = Machine.processor().getMemory();
	    	for(int i=0;i<pageSize;i++) {
	    		memory[tlbEntry.ppn*pageSize + i] = 0;  
	    	}
//	    	tlbEntry.dirty=true;
		}
		

		// Update Page Table and IPT
		syncEntry(pageTable[entry.vpn],tlbEntry);
		
//		pageTable[vpn] = tlbEntry;
		VMKernel.iptLock.acquire();
		VMKernel.ipt[tlbEntry.ppn].entry = pageTable[entry.vpn];
//		VMKernel.ipt[tlbEntry.ppn].entry = tlbEntry;
		VMKernel.iptLock.release();

		return tlbEntry;
	}
	
	public static void teToString(TranslationEntry t){
		System.out.println("///Process////");
		System.out.println("VPN: " + t.vpn);
		System.out.println("PPN: " + t.ppn);
		System.out.println("Valid: " + t.valid);
		System.out.println("ReadOnly: " + t.readOnly);
		System.out.println("Used: " + t.used);
		System.out.println("Dirty: " + t.dirty);
		System.out.println();
	}
	
	// Sync's entries
	public static void syncEntry(TranslationEntry old, TranslationEntry sync) {
		old.vpn = sync.vpn;
		old.ppn = sync.ppn;
		old.valid = sync.valid;
		old.readOnly = sync.readOnly;
		old.used = sync.used;
		old.dirty = sync.dirty;
	}

	private HashMap<Integer, CoffSection> vpnCoffMap = new HashMap<>();

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
