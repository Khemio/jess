# Jess - online chess

This project contains client and server. Server is built on top of custom implementation of HTTP 1.1 and WebSockets.
Static files are served from port 4221 and WebSocket communication goes through port 4220.

You can run this project with this commands, assuming your source folder is `src/main/java`:

``` bash
mvn compile
cd target/classes
java jess.Jess
```

or compile and run the script

``` bash
mvn compile
./run.sh
```
