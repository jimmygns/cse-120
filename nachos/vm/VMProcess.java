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
		System.out.println("saveState");
		for (int i = 0; i < Machine.processor().getTLBSize(); ++i) {
			entry = Machine.processor().readTLBEntry(i);

			if (entry.valid) {
				// Synch tlb entry to page table
				pageTable[entry.vpn] = new TranslationEntry(entry);  // Copy by value
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
		for (int i = 0; i < coff.getNumSections(); ++i) {
			CoffSection section = coff.getSection(i);

			for (int j = 0; j < section.getLength(); ++j) {
				vpnCoffMap.put(section.getFirstVPN() + j, section);
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
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

		int asdf = Machine.processor().getTLBSize();

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
			index = Lib.random(Machine.processor().getTLBSize());
			TranslationEntry victim = Machine.processor().readTLBEntry(index);
			// Sync pageTable with tlb entry
			pageTable[victim.vpn]=victim;
			//sync ipt TODO sync why?
			VMKernel.iptLock.acquire();
			VMKernel.ipt[victim.ppn].entry=victim;
			VMKernel.iptLock.release();
		}

		// Find the PTE of the TLB Miss
		int missVAddress = Machine.processor().readRegister(Processor.regBadVAddr);
		int missVPN = Processor.pageFromAddress(missVAddress);
		
		// Under strategy guide, says if the TLB entry is a write, synch TLB with PageTable of process after setting used and dirty to true?
		TranslationEntry tlbPTE = pageTable[missVPN];
		if(tlbPTE.valid) {
			Machine.processor().writeTLBEntry(index, tlbPTE);
		}
		else{
			Machine.processor().writeTLBEntry(index, handlePageFault(tlbPTE, missVPN));
		}
	}
	
	/**
	 *  
	 * @param entry -- PageTable Entry that we tried to find in TLB but caused miss
	 * @param vpn -- VPN of what we were trying to access
	 * @return
	 */
	private TranslationEntry handlePageFault(TranslationEntry entry, int vpn) {
		TranslationEntry tlbEntry = null;
		
		// Allocate a Physical Page
		UserKernel.memoryLock.acquire();
		if (!UserKernel.freePages.isEmpty()) {
			tlbEntry = new TranslationEntry(vpn, UserKernel.freePages.pop(), true, false, false, false); // Set readOnly and used bit?
		} else { //No free memory, need to evict a page
		    // Sync TLB entries; 
			TranslationEntry syncEntry;
			for(int i=0;i<Machine.processor().getTLBSize();i++){
				syncEntry = Machine.processor().readTLBEntry(i);
				if(syncEntry.valid){
					TranslationEntry old = pageTable[syncEntry.vpn];
					syncEntry(old,syncEntry);
					Lib.assertTrue(pageTable[syncEntry.vpn].vpn == syncEntry.vpn);
					Lib.assertTrue(pageTable[syncEntry.vpn].ppn == syncEntry.ppn);
					Lib.assertTrue(pageTable[syncEntry.vpn].valid == syncEntry.valid);
					Lib.assertTrue(pageTable[syncEntry.vpn].readOnly == syncEntry.readOnly);
					Lib.assertTrue(pageTable[syncEntry.vpn].used == syncEntry.used);
					Lib.assertTrue(pageTable[syncEntry.vpn].dirty == syncEntry.dirty);

					//pageTable[syncEntry.vpn]=syncEntry;
					VMKernel.iptLock.acquire();
					VMKernel.ipt[syncEntry.ppn].entry=syncEntry;
					VMKernel.iptLock.release();
				}
			}
			
//		    Select a victim for replacement; //Clock algorithm
			TranslationEntry toEvict;
			VMKernel.victimLock.acquire();    // Replaced code here TODO
			while (true) {
//				// TODO pinPage edge case
//				if(VMKernel.numOfPinnedPages == Machine.processor().getNumPhysPages()) {
//					condition.sleep();
//				}

				if(VMKernel.ipt[VMKernel.victim].pinCount==0){
					if(VMKernel.ipt[VMKernel.victim].entry.used==true){
						VMKernel.ipt[VMKernel.victim].entry.used=false;
//						VMKernel.victimLock.acquire();
						VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;
						if(VMKernel.victim==Integer.MAX_VALUE) {     // Make sure victim value does not exceed max
							VMKernel.victim = 0;
						}
//						VMKernel.victimLock.release();
					}
					else{
						break;
					}
				}
				else{
//					VMKernel.victimLock.acquire();
					VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;
					if(VMKernel.victim==Integer.MAX_VALUE) {     // Make sure victim value does not exceed max
						VMKernel.victim = 0;
					}
//					VMKernel.victimLock.release();
				}
			}
			VMKernel.iptLock.acquire();
			toEvict = VMKernel.ipt[VMKernel.victim].entry;      // Victim in the physical page table entry
			VMKernel.iptLock.release(); // TODO might not need
			VMKernel.victimLock.release();    // Replaced code here TODO
			tlbEntry = new TranslationEntry(vpn, toEvict.ppn, true, false, false, false); // TODO set readOnly and used bit?
			System.out.println("toevict: " + toEvict.dirty);
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
					spn=VMKernel.freeSwapPages.size();
					VMKernel.freeSwapPages.add(spn);
				}
				byte[] memory = Machine.processor().getMemory();
				int offset = toEvict.ppn*pageSize;
				VMKernel.swap.write(spn*pageSize, memory,offset, pageSize);   // Write from phys mem to disk
				VMKernel.swapLock.release();

				//Invalidate PTE and TLB entry of the victim page
				VMKernel.iptLock.acquire();  // TODO do not need to if this works, here for assertion right now
				//VMKernel.ipt[toEvict.ppn].modifyPageFrame(this.pid, entry);
				toEvict.valid=false;
				toEvict.ppn = spn; // Store spn in ppn val
				Lib.assertTrue(VMKernel.ipt[toEvict.ppn].entry.valid==false);
				Lib.assertTrue(VMKernel.ipt[toEvict.ppn].entry.ppn==spn);
//				VMKernel.ipt[toEvict.ppn].entry.valid=false;    // Refers to PTE of victim process TODO correct memory?
				VMKernel.iptLock.release();
			
			}
			
		}
		UserKernel.memoryLock.release();

		// Now read the appropriate data into memory
		
		// TODO do we need lock for machine processor memory?
		
		
		// Check if the TLB TranslationEntry is dirty - If so, Swap In, already checks for 
		if (entry.dirty) {                    // Is in disk
			// Read swap to get data
			byte[] memory = Machine.processor().getMemory();
			// entry.ppn currently refers to entry's swp, tlbEntry refers to ppn
			if(VMKernel.swap.read(entry.ppn*pageSize,memory, tlbEntry.ppn*pageSize, pageSize) <= 0) {
				System.out.println("Error, did not read memory"); // TODO change error
			}
//			entry.dirty = false;   // No longer dirty since paging in
		} else if(vpn < vpnCoffMap.size()) {     // Is coff section
			// Check vpn belongs to a CoffSection
			CoffSection section = vpnCoffMap.get(vpn);
			pageTable[vpn].readOnly = section.isReadOnly();
			// if coff not, set dirty bit
			tlbEntry.readOnly = pageTable[vpn].readOnly;
			section.loadPage(vpn - section.getFirstVPN(), tlbEntry.ppn);  // SPN is vpn - section.get
		} else {  // Not a COFF section, zero out stack page
			// Get memory, for that section of memory (bytes from start index to startIndex + page size), 0
	    	byte[] memory = Machine.processor().getMemory();
	    	for(int i=0;i<pageSize;i++) {
	    		memory[tlbEntry.ppn*pageSize + i] = 0;  
	    	}
//	    	entry.dirty=true;
		}
		

		// Update Page Table and IPT
		pageTable[vpn] = tlbEntry;
		VMKernel.iptLock.acquire();
		VMKernel.ipt[tlbEntry.ppn].entry = tlbEntry;
		VMKernel.iptLock.release();

		return tlbEntry;
/*



		UserKernel.memoryLock.acquire();
		if (!UserKernel.freePages.isEmpty()) {
			assignedPPN = UserKernel.freePages.pop();
			entry = new TranslationEntry(vpn, assignedPPN, true, false, false, false);
			UserKernel.memoryLock.release();
		}  else {


		  

		    // TODO implement this
			// Check if everything

			// Check if entry needs to be loaded from swap
			if(entry.valid==false) {
				if(entry.dirty==true) {
					
				}
				else if(entry.dirty==false) { // TODO how is this loaded into physical memory
//					// Means COFF section
//					// Load COFF sections
//					for (int s = 0; s < coff.getNumSections(); ++s) {
//						CoffSection section = coff.getSection(s);
//
//						Lib.debug(dbgProcess, "\tinitializing " + section.getName() + " section (" + section.getLength() + " pages)");
//
//						for (int i = 0; i < section.getLength(); ++i) {
//							int tempVPN = section.getFirstVPN() + i;
//
//							// Set this page to read-only
//							pageTable[tempVPN].readOnly = section.isReadOnly();
//							section.loadPage(i, pinVirtualPage(tempVPN, false));
//						}
//					}
				}
			}

		    // TODO Invalidate PTE and TLB entry of the victim page
		}
		*/
		//return entry;

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
