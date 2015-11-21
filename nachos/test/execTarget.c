#include "syscall.h"

int main(int argc, char* argv[]) {
	int id =exec("write4.coff",0,0);
	join(id,0);
	char *str = "\nEnd of execTarget\n";
	
	while(*str) {
		write(1,str,1);
		str++;
	}
}
