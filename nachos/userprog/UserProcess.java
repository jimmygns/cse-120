package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.vm.VMKernel;

import java.io.EOFException;
import java.util.HashMap;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		UserKernel.processLock.acquire();
		this.pid = UserKernel.nextProcessID++;
		UserKernel.processLock.release();

		// Set 0 and 1 of table to execute files
		fileDescriptorTable[0] = UserKernel.console.openForReading();
		fileDescriptorTable[1] = UserKernel.console.openForWriting();

		for (int i = 2; i < maxFiles; ++i) {
			fileDescriptorTable[i] = null;
		}
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 *
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 *
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		UserKernel.numRunningProcesses++;

		new UThread(this).setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 *
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		int totalTransferred = 0;

		while (length > 0) {
			int vpn = Processor.pageFromAddress(vaddr);
			int off = Processor.offsetFromAddress(vaddr);
			int transfer = Math.min(length, pageSize - off);
			int ppn = pinVirtualPage(vpn, false);

			if (ppn == -1) {
				break;
			}

			System.arraycopy(memory, ppn * pageSize + off, data, offset, transfer);

			unpinVirtualPage(vpn);

			vaddr += transfer;
			offset += transfer;
			totalTransferred += transfer;
			length -= transfer;
		}

		return totalTransferred;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 *
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		int totalTransferred = 0;

		while (length > 0) {
			int vpn = Processor.pageFromAddress(vaddr);
			int off = Processor.offsetFromAddress(vaddr);
			int transfer = Math.min(length, pageSize - off);
			int ppn = pinVirtualPage(vpn, true);

			if (ppn == -1) {
				break;
			}

			System.arraycopy(data, offset, memory, ppn * pageSize + off, transfer);

			unpinVirtualPage(vpn);

			vaddr += transfer;
			offset += transfer;
			totalTransferred += transfer;
			length -= transfer;
		}

		return totalTransferred;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 *
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// Make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// Make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// Program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// Next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// And finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// Store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 *
	 * @return <tt>true</tt> if the sections were successfully loaded.
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
			// Write entry into page table then give it memory
			pageTable[i] = new TranslationEntry(i, UserKernel.freePages.pop(), true, false, false, false);
		}

		UserKernel.memoryLock.release();

		// Load sections
		for (int s = 0; s < coff.getNumSections(); ++s) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName() + " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); ++i) {
				int vpn = section.getFirstVPN() + i;

				// Set this page to read-only
				pageTable[vpn].readOnly = section.isReadOnly();
				section.loadPage(i, pinVirtualPage(vpn, false));
			}
		}

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		UserKernel.memoryLock.acquire();

		// Release all pages stored
		for(TranslationEntry t : pageTable) {
			UserKernel.freePages.push(t.ppn);
		}

		UserKernel.memoryLock.release();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// By default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// Initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// Initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * handle the halt() system call.
	 */
	private int handleHalt() {
		// Check if root process, if not, return immediately
		if (pid != 0) {
			return -1;
		}

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");

		return 0;
	}

	/**
	 * handle the creat() system call.
	 */
	private int handleCreat(int nameAddress) {
		// Read name from virtual memory
		String filename = readVirtualMemoryString(nameAddress, stringMax);

		if (filename == null) {
			return -1;
		}

		OpenFile file = ThreadedKernel.fileSystem.open(filename, true);

		if (file == null) {
			return -1;
		}

		for (int i = 0; i < fileDescriptorTable.length; ++i) {
			if (fileDescriptorTable[i] == null) {
				fileDescriptorTable[i] = file;
				return i;
			}
		}

		// No null entry found, return failure
		return -1;
	}

	/**
	 * handle the open() system call.
	 */
	private int handleOpen(int nameAddress) {
		// Read name from virtual memory
		String filename = readVirtualMemoryString(nameAddress, stringMax);

		if (filename == null) {
			return -1;
		}

		OpenFile file = ThreadedKernel.fileSystem.open(filename, false);

		if (file == null) {
			return -1;
		}

		for (int i = 0; i < fileDescriptorTable.length; ++i) {
			if (fileDescriptorTable[i] == null) {
				fileDescriptorTable[i] = file;
				return i;
			}
		}

		// No null entry found, return failure
		return -1;
	}

	/**
	 * handle the read() system call.
	 *
	 */
	private int handleRead(int descriptor, int bufferAddress, int length) {
		// Check if valid file descriptor and count
		if (descriptor < 0 || descriptor > fileDescriptorTable.length - 1 || length < 0) {
			return -1;
		}

		// Check if entry in file descriptor table is valid
		OpenFile file = fileDescriptorTable[descriptor];
		if (file == null) {
			return -1;
		}

		int totalRead = 0;
		while (length > 0) {
			int transfer = Math.min(length, ioBufferSize);
			int actual = file.read(ioBuffer, 0, transfer);

			// Check if bytes were actually read
			if (actual <= 0) {
				return -1;
			}

			// Write data into the buffer
			actual = writeVirtualMemory(bufferAddress, ioBuffer, 0, actual);

			// Check if bytes were written
			if (actual <= 0) {
				return -1;
			}

			bufferAddress += actual;
			length -= actual;
			totalRead += actual;

			if (actual < transfer) {
				break;
			}
		}

		return totalRead;
	}

	/**
	 * handle the write() system call.
	 */
	private int handleWrite(int descriptor, int bufferAddress, int length) {
		// Check if valid file descriptor and count
		if (descriptor < 0 || descriptor > fileDescriptorTable.length - 1 || length < 0) {
			return -1;
		}

		// Check if entry in file descriptor table is valid
		OpenFile file = fileDescriptorTable[descriptor];
		if (file == null) {
			return -1;
		}

		int totalWritten = 0;
		int remaining = length;
		while (remaining > 0) {
			int transfer = Math.min(remaining, ioBufferSize);
			int actual = readVirtualMemory(bufferAddress, ioBuffer, 0, transfer);

			actual = file.write(ioBuffer, 0, actual);

			// Check if bytes were actually written
			if (actual == -1) {
				return totalWritten == 0 ? -1 : totalWritten;
			}

			bufferAddress += actual;
			remaining -= actual;
			totalWritten += actual;

			if (actual < transfer) {
				break;
			}
		}

		return totalWritten != length ? -1 : totalWritten;
	}

	/**
	 * handle the close() system call.
	 */
	private int handleClose(int descriptor) {
		// Check if valid file descriptor
		if (descriptor < 0 || descriptor > fileDescriptorTable.length - 1) {
			return -1;
		}

		// Check if entry in file descriptor table is valid
		if (fileDescriptorTable[descriptor] == null) {
			return -1;
		}

		fileDescriptorTable[descriptor].close();
		fileDescriptorTable[descriptor] = null;

		return 0;
	}

	/**
	 * handle the unlink() system call.
	 */
	private int handleUnlink(int nameAddress) {
		// Read name from virtual memory
		String filename = readVirtualMemoryString(nameAddress, stringMax);

		// Check if file exists
		if (filename == null) {
			return -1;
		}

		return ThreadedKernel.fileSystem.remove(filename) ? 0 : -1;
	}

	/**
	 * handle the exec() system call.
	 */
	private int handleExec(int coffName, int argc, int argv) {
		// Read name from virtual memory
		String filename = readVirtualMemoryString(coffName, stringMax);

		// Check validity of filename and argc
		if (filename == null || argc < 0 || argc > maxArgs)
			return -1;

		// Check for proper .coff extension
		int index = filename.lastIndexOf('.');
		if (index == -1 || !filename.substring(index + 1, filename.length()).equals("coff")) {
			return -1;
		}

		// Check if the argv is readable
		byte[] data = new byte[argc * 4];
		if (readVirtualMemory(argv, data) < data.length) {
			return -1;
		}

		// Get arguments
		String[] arguments = new String[argc];
		for (int i = 0; i < argc; ++i) {
			arguments[i] = readVirtualMemoryString(Lib.bytesToInt(data, i * 4), stringMax);

			if (arguments[i] == null) {
				return -1;
			}
		}

		// Create new process, save necessary id's, and execute it
		int childPID = -1;
		UserProcess child = UserProcess.newUserProcess();
		child.parentProcess = this;

		UserKernel.processLock.acquire();

		// Execute the child and add to parent's list of child processes
		if (child.execute(filename, arguments)){
			childPID = child.pid;
			childProcesses.put(childPID, child);
		}

		UserKernel.processLock.release();

		return childPID;
	}

	/**
	 * handle the join() system call.
	 */
	private int handleJoin(int pid, int statusAddress) {
		if (childProcesses.get(pid) == null) {
			return -1;
		}

		int result = 0;

		UserKernel.processLock.acquire();

		// Put current process thread to sleep until child (specified by pid arg) has exited
		while (!childProcessesExitStatus.containsKey(pid))
			childFinished.sleep();

		Integer status = childProcessesExitStatus.get(pid);

		if (status != null) {
			result = 1;
			writeVirtualMemory(statusAddress, Lib.bytesFromInt(status.intValue()));
		}

		UserKernel.processLock.release();

		return result;
	}

	/**
	 * handle the exit() system call.
	 */
	private int handleExit(int status) {
		// Close all file descriptors
		for (OpenFile f : fileDescriptorTable) {
			if (f != null) {
				f.close();
				f = null;
			}
		}

		// Free all memory
		unloadSections();

		coff.close();

		UserKernel.processLock.acquire();

		// If there's a parent process, add the exit status to the parent's exist status table
		if (parentProcess != null) {
			parentProcess.childProcessesExitStatus.put(pid, exceptionReturn ? null : status);
			parentProcess.childFinished.wake();
		}

		System.out.println("Exit status: " + status); // TODO remove
		
		// If last process, halt the whole machine
		if (--UserKernel.numRunningProcesses == 0) {
			Kernel.kernel.terminate();
		}

		UserKernel.processLock.release();

		KThread.finish();

		Lib.assertNotReached("KThread.finish() did not finish thread!");

		return 0;
	}

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 *
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>pid
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 *
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
			case syscallHalt:
				return handleHalt();
			case syscallCreate:
				return handleCreat(a0);
			case syscallOpen:
				return handleOpen(a0);
			case syscallRead:
				return handleRead(a0, a1, a2);
			case syscallWrite:
				return handleWrite(a0, a1, a2);
			case syscallUnlink:
				return handleUnlink(a0);
			case syscallClose:
				return handleClose(a0);
			case syscallExec:
				return handleExec(a0, a1, a2);
			case syscallJoin:
				return handleJoin(a0, a1);
			case syscallExit:
				return handleExit(a0);
			default:
				handleExit(1);       // Changed made to follow sol'n
				Lib.debug(dbgProcess, "Unknown syscall " + syscall);
				Lib.assertNotReached("Unknown system call!");
		}

		return 0;
	}

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
			case Processor.exceptionSyscall:
				int result = handleSyscall(processor.readRegister(Processor.regV0),
						processor.readRegister(Processor.regA0),
						processor.readRegister(Processor.regA1),
						processor.readRegister(Processor.regA2),
						processor.readRegister(Processor.regA3));
				processor.writeRegister(Processor.regV0, result);
				processor.advancePC();
				break;
			default:
				Lib.debug(dbgProcess, "Unexpected exception: " + Processor.exceptionNames[cause]);
				exceptionReturn = true;
				handleExit(0);
				Lib.assertNotReached("Unexpected exception");
		}
	}

	protected int pinVirtualPage(int vpn, boolean isUserWrite) {
		if (vpn < 0 || vpn >= pageTable.length)
			return -1;

		TranslationEntry entry = pageTable[vpn];
		if (!entry.valid || entry.vpn != vpn)
			return -1;

		if (isUserWrite) {
			if (entry.readOnly)
				return -1;
			entry.dirty = true;
		}

		
		// Synch to tlb and ipt
		entry.used = true;
		VMKernel.pinPage(entry.ppn);
		return entry.ppn;
	}

	protected void unpinVirtualPage(int vpn) {
		// TODO unpin the physical page
		// TODO wake up the sleeping threads on edge case
		// conditionVariable.wakeAll();
	}

	// The number of pages in the program's stack
	protected final int stackPages = 8;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final int stringMax = 256;

	protected static final int maxArgs = 16;

	protected static final int maxFiles = 16;

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	// The program being run by this process
	protected Coff coff;

	// This process's page table
	protected TranslationEntry[] pageTable;

	// The number of contiguous pages occupied by the program
	protected int numPages;

	private int initialPC, initialSP;

	private int argc, argv;

	protected int pid;

	// Tracks whether process had an exception or not
	private boolean exceptionReturn = false;

	private Condition childFinished = new Condition(UserKernel.processLock);

	private UserProcess parentProcess;

	private HashMap<Integer, UserProcess> childProcesses = new HashMap<>();

	private HashMap<Integer, Integer> childProcessesExitStatus = new HashMap<>();

	private OpenFile[] fileDescriptorTable = new OpenFile[maxFiles];

	private byte[] ioBuffer = new byte[ioBufferSize];

	private static final int ioBufferSize = 1024;

	private KThread processThread;
}