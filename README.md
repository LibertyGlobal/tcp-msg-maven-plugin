# Description

Use case
---

Our build script was starting `Postgres` container with `maven-docker-plugin` and then used the DB instance to generate some `JOOQ` artifacts in later steps.

```
<plugin>
    ...
    <artifactId>docker-maven-plugin</artifactId>
    ...
    <configuration>
        <images>
            <image>
                ...
                <name>postgres:11</name>
                ...
            </image>
        </images>
    </configuration>

    <executions>
        <execution>
            <id>start-postgres-container</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>start</goal>
            </goals>
        </execution>
        <execution>
            <id>stop-postgres-container</id>
            <phase>process-sources</phase>
            <goals>
                <goal>stop</goal>
            </goals>
        </execution>
    </executions>

</plugin>
```

But in case there was any error with operations between `start` and `stop` above, then maven left a running container and consecutive build attempts would fail (in this case we were using same port) so one had to put down the leftover container manually first.

Therefore the question arose if there is any way/plugin in Maven to specify some 'finally' action/phase? So that in case of failure in build scripts one would still be able to release the resources which might've been already reserved.

After some initial research it did not seem to be possible in pure maven to do such a cleanup - neither with built-in nor with existing community plugins.

Proposal
---

The goal could be achieved applying similar solution to what `testcontainers` are doing using **Ryuk**: https://github.com/testcontainers/moby-ryuk
        
```
<image>
    <alias>ryuk-summoned</alias>
    <name>testcontainers/ryuk:0.3.0</name>
    <run>
        <ports>
            <port>ryuk.port:8080</port>
        </ports>
        <volumes>
            <bind>
                <volume>/var/run/docker.sock:/var/run/docker.sock</volume>
            </bind>
        </volumes>
        <autoRemove>true</autoRemove>
    </run>
</image>
```

The first issue is that mapping volume is OS specific - works perfectly on Linux and Mac but not on Windows.
But most probably MVN profiles might be used to provide OS specific host path for mapping. 

**NOTE:** Mind that Docker on Windows runs in linux virtual box anyway - so the path should be similar. The question is if it needs any prefix like `/c/...` for bash running on Windows to resolve the path correctly?

The second challenge is that there must be feed with a hart-beat towards Ryuk container at least 1 per 10s through a TCP socket containing death note, eg. like this: `printf "label=something_to_kill" | nc localhost 8080`. See: https://github.com/testcontainers/moby-ryuk/issues/17

This is easy to achieve with below maven exec plugin and a simple `setup-ryuk.sh` script that would be calling the command mentioned above. But this would not be so straight forward on Window OS on the other hand.

Solution
---

To make this platform independent the best way seemed to come up with own Maven plug*in sending messages to TCP socket.
This plugin was created specifically for that goal in the context of above use case. It starts a daemon which makes multiple attempts to send the message to the network socket with command specified in configuration.  

Building
---

To build and install execute the following command: 

```
mvn clean install plugin:descriptor
```

Usage example
---

```
<plugin>
    <groupId>com.libertyglobal.common.maven</groupId>
    <artifactId>tcp-msg-maven-plugin</artifactId>
    ...
    <executions>
        <execution>
            <id>write-death-note-for-postgres</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>tcpmsg</goal>
            </goals>
            <configuration>
                <port>${ryuk.port}</port>
                <msg>label=killme</msg>
                <repeatAmount>2</repeatAmount>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Configuration parameters
---

- `host, defaultValue = "localhost" / String` - Hostname where to send the command message.

- `repeatAmount, defaultValue = 1 / Integer` - How many attempts the plugin should do to send the message.

- `intervalSec, defaultValue = 5 / Integer` - How often to attempt sending the message. First interval takes place just after start befor first attempt.

- `port / Integer` - TCP port where to send the command message.

- `msg / String` - Message to be sent on above port.

Using it as local dependency in project
---

To use it in project specify:

```
mvn org.apache.maven.plugins:maven-install-plugin:2.3.1:install-file -Dfile=$HOME/.m2/repository/com/libertyglobal/common/maven/tcp-msg-maven-plugin/1.0.0/tcp-msg-maven-plugin-1.0.0.jar -DpomFile=$HOME/.m2/repository/com/libertyglobal/common/maven/tcp-msg-maven-plugin/1.0.0/tcp-msg-maven-plugin-1.0.0.pom -DgroupId=com.libertyglobal.common.maven -DartifactId=tcp-msg-maven-plugin -Dversion=1.0.0 -Dpackaging=jar -DlocalRepositoryPath=./lib plugin:descriptor
```

Then move the `lib` dir to your project and define it as local plugins repository for your Maven installation:

```
<pluginRepositories>
    <pluginRepository>
        <id>local-plugins-repo</id>
        <url>file://${project.basedir}/lib</url>
    </pluginRepository>
</pluginRepositories>
```

Then you can add it as standard dependency. 

```
<dependency>
    <groupId>com.libertyglobal.common.maven</groupId>
    <artifactId>tcp-msg-maven-plugin</artifactId>
    <version>1.0.0</version>
</dependency>
```