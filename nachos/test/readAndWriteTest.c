#include "syscall.h"
#include "stdio.h"
int main() {
  
  int f = creat("text.txt");
  char *s = "writing into the file\n";
  int num = write(f,s,100);
  char *newStr="";
  read(f,newStr,5);
  printf(newStr); 
}
