#include "stdio.h"
#include "stdlib.h"

int main(int argc, char *argv[]) {
    int i = 0;

    int foo[2];
    foo[1] = 4;

    while (i < argc) {
        printf(argv[i]);
        ++i;
    }

    return 0;
}
