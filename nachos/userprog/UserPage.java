package nachos.userprog;

public class UserPage {
	public UserPage(int vpn, int ppn, int offset) {
		this.vpn = vpn;
		this.ppn = ppn;
		this.offset = offset;
	}
	
	public void setOffset(int offset) {
		this.offset = offset;
	}
	
	public int getOffset() {
		return offset;
	}
	
	public int getVPN() {
		return vpn;
	}
	
	public int getPPN() {
		return ppn;
	}
	
	private int offset;
	private int vpn;
	private int ppn;

}
