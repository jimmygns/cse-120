#include "stdio.h"
#include "stdlib.h"

int main() {
    char *args[2];
    char *name = "execSub.coff";
    int status;

    args[0] = name;
    args[1] = "to my balls\n";

    int pid = exec(name, 2, args);
    join(pid, &status);

    printf("Status of pid: %d\n", status);

    return 1;
}
