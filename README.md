# unboundid-ldap-server
An UnboundID LDAP server written in Java using the UnboundID SDK. 

## Run
On the command prompt, execute:

```bash
gradlew
```

Connect to the LDAP instance at `ldap://localhost:1389`

## Configuration

Under `src/main/resources`:

* Schema is defined via `standard-ldap.schema
* LDAP server settings are defined via `ldap.properties`
* LDAP base layouts are defined via `ldap-base.ldif` and `users-groups.ldif`
* Log configuration is defined via `log4j2.xml`
