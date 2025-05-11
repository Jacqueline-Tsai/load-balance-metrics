
LIB_DIR = $(PWD)/../lib
HOST = 127.0.0.1
SERVER_PORT = 3000
CLOUD_PORT = 3001
DATABASE = $(LIB_DIR)/db1.txt
SPEC = c-1000-sss
HOUR = 0
DURATION = 20

all: Server.class

%.class: %.java
	javac $<

server:
	rmiregistry 3000 && export CLASSPATH=$(PWD):$(LIB_DIR) && java Server $(HOST) $(PORT) 1

simulation:
	export CLASSPATH=$(PWD):$(LIB_DIR) && java Cloud $(CLOUD_PORT) $(DATABASE) $(SPEC) $(HOUR)
DURATION = 20

clean:
	rm -f *.class
