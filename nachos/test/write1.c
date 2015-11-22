/*
 * write.c
 *
 * Write a string to stdout, one byte at a time.  Does not require any
 * of the other system calls to be implemented.
 */

#include "syscall.h"

int
main (int argc, char *argv[])
{
    char *str = "\nroses are red\nviolets are blue\nI love Nachos\nand so do you\n\n";
    int f = open("text.txt");
 
    while (*str) {
	write (f, str, 1);
	str++;
    }

    return 0;
}
