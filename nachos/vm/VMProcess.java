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
		UserKernel.memoryLock.acquire();
		System.out.println("NumPages Needed: " + numPages);
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
		
		UserKernel.memoryLock.release();

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
			System.out.println("no invalid");
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
		int tlbVAddr = Machine.processor().readRegister(Processor.regBadVAddr);
		int tlbVPN = Processor.pageFromAddress(tlbVAddr);
		System.out.println("tlbVPN: " + tlbVPN);
		

		TranslationEntry tlbPTE = pageTable[tlbVPN];
		System.out.println("tlbValid?: " + tlbPTE.valid);
		if(tlbPTE.valid) {
			System.out.println("isvalid");
			Machine.processor().writeTLBEntry(index, tlbPTE);
		}
		else{
			TranslationEntry temp = handlePageFault(tlbPTE, tlbVPN);
			System.out.println("validbit: " + temp.valid);
			Machine.processor().writeTLBEntry(index, temp);
			
//			Machine.processor().writeTLBEntry(index, handlePageFault(tlbPTE, tlbVPN));
		}
	}
	
	/**
	 *  
	 * @param entry -- PageTable Entry that we tried to find in TLB but caused miss
	 * @param vpn -- VPN of what we were trying to access
	 * @return
	 */
	private TranslationEntry handlePageFault(TranslationEntry tlbEntry, int vpn) {
//		System.out.println("Processes: " + UserKernel.numRunningProcesses);
//		System.out.println("Processor pages: " + Machine.processor().getNumPhysPages());
		int assignedPPN;
		TranslationEntry entry = null;

		
		
		
		// Allocate a Physical Page
		UserKernel.memoryLock.acquire();
		if (!UserKernel.freePages.isEmpty()) {
			assignedPPN = UserKernel.freePages.pop();
			entry = new TranslationEntry(vpn, assignedPPN, true, false, false, false);
		}  else {
			System.out.println("noFreePages");
			System.out.println("Free pages: "+ UserKernel.freePages.size());
			assignedPPN = 3; // Dummy val
			// No free memory - need to evict a page

			// Symc TLB entries

			// Select a victim for replacement (Clock Algorithm

			// Swap out
			// if (victim.dirty) {
			// }

			// Invalidate PTE and TLB entry of the victim page
		}
		UserKernel.memoryLock.release();

		
		// Check if the TLB TranslationEntry is dirty - If so, Swap In
		if (tlbEntry.dirty) {                    // Is in memory

		} else if(vpn < vpnCoffMap.size()) {     // Is coff section
			// Check vpn belongs to a CoffSection
			CoffSection section = vpnCoffMap.get(vpn);
			pageTable[vpn].readOnly = section.isReadOnly();
			section.loadPage(vpn - section.getFirstVPN(), entry.ppn); 
		} else {  // Not a COFF section, zero out stack page
			// Get memory, for that section of memory (bytes from start index to startIndex + page size), 0
	    	byte[] memory = Machine.processor().getMemory();
	    	for(int i=0;i<pageSize;i++) {
	    		memory[assignedPPN*pageSize + i] = 0;
	    	}

			
		}
		

		// Update Page Table and IPT
		System.out.println("vpn: " + vpn);
		pageTable[vpn] = entry;
		System.out.println("pagetableValid: " + pageTable[vpn].valid);
		VMKernel.iptLock.acquire();
		VMKernel.ipt[entry.ppn].entry = entry;
		VMKernel.iptLock.release();

		return entry;
/*



		UserKernel.memoryLock.acquire();
		if (!UserKernel.freePages.isEmpty()) {
			assignedPPN = UserKernel.freePages.pop();
			entry = new TranslationEntry(vpn, assignedPPN, true, false, false, false);
			UserKernel.memoryLock.release();
		}  else {
		    //No free memory, need to evict a page

			//TODO
//		    Sync TLB entries; 
			TranslationEntry entryNeedToBeSynced;
			for(int i=0;i<Machine.processor().getTLBSize();i++){
				entryNeedToBeSynced = Machine.processor().readTLBEntry(i);
				if(entryNeedToBeSynced.valid){
					pageTable[entryNeedToBeSynced.vpn]=entryNeedToBeSynced;
					VMKernel.iptLock.acquire();
					VMKernel.ipt[entryNeedToBeSynced.ppn].entry=entryNeedToBeSynced;
					VMKernel.iptLock.release();
				}
			}
//		    Select a victim for replacement; //Clock algorithm
			TranslationEntry toEvict;
			while (true) {
//				// TODO pinPage edge case
//				if(VMKernel.numOfPinnedPages == Machine.processor().getNumPhysPages()) {
//					condition.sleep();
//				}

				//sleep if all the pps are pinned
				if(VMKernel.ipt[VMKernel.victim].pinCount==0){
					if(VMKernel.ipt[VMKernel.victim].entry.used==true){
						VMKernel.ipt[VMKernel.victim].entry.used=false;
						VMKernel.victimLock.acquire();
						VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;
						VMKernel.victimLock.release();
					}
					else{
						//toEvict=VMKernel.ipt[VMKernel.victim].entry;
						break;
					}
				}
				else{
					VMKernel.victimLock.acquire();
					VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length;
					VMKernel.victimLock.release();
				}
			}
			toEvict = VMKernel.ipt[VMKernel.victim].entry;
			assignedPPN = toEvict.ppn;

		    if (toEvict.dirty) {
		    	VMKernel.swapLock.acquire();
		    	int spn=-1;
		    	for(int i =0;i<VMKernel.freeSwapPages.size();i++){
		    		if(VMKernel.freeSwapPages.get(i)==null){
		    			spn=i;
		    			VMKernel.freeSwapPages.set(spn, spn);
		    			break;
		    		}
		    	}
		    	if(spn==-1){
		    		spn=VMKernel.freeSwapPages.size();
		    		VMKernel.freeSwapPages.add(spn);
		    	}
		    	//TODO
		    	byte[] memory = Machine.processor().getMemory();
		    	//need to check this with someone
		    	int offset = toEvict.ppn*pageSize;
		    	VMKernel.swap.write(spn*pageSize, memory,offset, pageSize);
		    	VMKernel.swapLock.release();
		    	//TODO
		    	//maybe this should happen after
		    	//entry= new TranslationEntry(vpn, assignedPPN, true, false, false, false);

		    	//Invalidate PTE and TLB entry of the victim page
		    	//toEvict.ppn=spn;
		    	//toEvict.valid=false;
		    	pageTable[toEvict.vpn].valid=false;
		    	VMKernel.iptLock.acquire();
		    	//VMKernel.ipt[toEvict.ppn].modifyPageFrame(this.pid, entry);
		    	VMKernel.ipt[toEvict.ppn].entry.valid=false;
		    	VMKernel.iptLock.release();

		    }

		    // TODO implement this
			// Check if everything

			// Check if entry needs to be loaded from swap
			if(entry.valid==false) {
				if(entry.dirty==true) {
					// Read swap to get data
					byte[] memory = Machine.processor().getMemory();
					// entry.ppn currently refers to entry's swp
					if(VMKernel.swap.read(entry.ppn*pageSize,memory, assignedPPN*pageSize, pageSize) <= 0) {
						System.out.println("Error, did not read memory"); // TODO change error
					}
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

	private HashMap<Integer, CoffSection> vpnCoffMap = new HashMap<>();

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
