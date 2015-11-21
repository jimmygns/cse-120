/*
 * This thing should print a thing, then execute a new thing
 */
#include "syscall.h"

int main(int argc, char* argv[]) {
	int id = exec("write4.coff",0,0);
	join(3,0);
	int id2 = exec("execTarget.coff",0,0);
	//join(id2,0);
	char *str = "\nEnd of testExec\n";
	while(*str) {
		write(1,str,1);
		str++;
	}
	return 0;
}
