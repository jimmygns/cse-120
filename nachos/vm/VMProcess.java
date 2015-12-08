package nachos.vm;

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
		
		UserKernel.memoryLock.release();
		// TODO Lazy-loading

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
		// TODO, we write to the TLB incorrectly, check incoming Vpn make sure it writes to table correctly
		TranslationEntry entry;
		int index=-1;
		//looking for invalid entry
		for(int i=0;i<Machine.processor().getTLBSize();i++){
			entry = Machine.processor().readTLBEntry(i);
			if(entry.valid==false){
				index=i;
				break;
			}
		}
		// No invalid entries so only process entries right now, randomly evict one, and sync to the page table
		if(index==-1){
			// Get random index, and make that the victim
			index = Lib.random(Machine.processor().getTLBSize());
			TranslationEntry victim = Machine.processor().readTLBEntry(index);
			// Sync pageTable with tlb entry
			pageTable[victim.vpn]=victim;
			//sync ipt
			VMKernel.iptLock.acquire();
			VMKernel.ipt[victim.ppn].entry=victim;
			VMKernel.iptLock.release();
		}
		int vaddr = Machine.processor().readRegister(Processor.regBadVAddr);
		int vpn = Processor.pageFromAddress(vaddr);
		TranslationEntry PTE = pageTable[vpn];
		if(PTE.valid)
			Machine.processor().writeTLBEntry(index, PTE);
		else{
			Machine.processor().writeTLBEntry(index, handlePageFault(PTE,vpn));
		}
	}
	
	
	/**
	 *  
	 * @param entry -- PageTable entry to get free page for
	 * @param vpn -- VPN trying to access
	 * @return
	 */
	private TranslationEntry handlePageFault(TranslationEntry entry, int vpn) {
		int assignedPPN;

		
		if (!UserKernel.freePages.isEmpty()) 
		{
			UserKernel.memoryLock.acquire();
			assignedPPN = UserKernel.freePages.pop();
			entry= new TranslationEntry(vpn, assignedPPN, true, false, false, false);
			UserKernel.memoryLock.release();
		} 
		else { 
		    //No free memory, need to evict a page 
			
			//TODO
//		    Sync TLB entries; 
			for(int i=0;i<Machine.processor().getTLBSize();i++){
				entry = Machine.processor().readTLBEntry(i);
				if(entry.valid){
					pageTable[entry.vpn]=entry;
					VMKernel.iptLock.acquire();
					VMKernel.ipt[entry.ppn].entry=entry;
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
						toEvict=VMKernel.ipt[VMKernel.victim].entry;
						break;
					}
				}
			    VMKernel.victimLock.acquire();
			    VMKernel.victim = (VMKernel.victim + 1) % VMKernel.ipt.length; 
			    VMKernel.victimLock.release();
			} 
			toEvict = VMKernel.ipt[VMKernel.victim].entry; 
			assignedPPN = toEvict.ppn;
			
		    if (toEvict.dirty&&!toEvict.readOnly) { 
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
		    		VMKernel.freeSwapPages.add(VMKernel.freeSwapPages.size());
		    	}
		    	//TODO
		    	byte[] memory = Machine.processor().getMemory();
		    	//need to check this with someone
		    	int offset = toEvict.ppn*pageSize;
		    	VMKernel.swap.write(spn*pageSize, memory,offset, pageSize);
		    	VMKernel.swapLock.release();
		    	entry= new TranslationEntry(vpn, assignedPPN, true, false, false, false);
		    	toEvict.ppn=spn;
		    	toEvict.valid=false;
		    	VMKernel.ipt[toEvict.ppn].modifyPageFrame(this.pid, entry);
		    	
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
		return entry;

	}
	
	
	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
