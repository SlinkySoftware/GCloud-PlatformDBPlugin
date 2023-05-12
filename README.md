# Management SQL Database Plugin #

This plugin will connect to a Microsoft SQL Database and provide the ability to return
results of single SELECT query. It's purpose is to connect to the GCloud tools management
database.

It utilises the single item lookup URL as part of the plugin framework:

```http://hostname/api/v1/mgmtSqlPlugin/record/<RECORDID>?queryType=<TYPE>```, where ```<RECORDID>``` is the unique ID to find, and ```<TYPE>``` is the query type to use (see below).

The query is required to return a SINGLE row only for a given data type. Returning multiple rows will cause an error.

### Configuration ### 

The plugin configuration file requires the following mandatory fields:

* ```cloud.database.url``` - JDBC URL to the MSSQL Instance - this should be in the format of ```jdbc:sqlserver://hostname:port``` - no properties should be specified on the URL
* ```cloud.database.username``` - Username for accessing the MSSQL Database
* ```cloud.database.password``` - Encrypted password (beginning with ```ENC:```) or unencrypted password to the MSSQL instance. The decryption is performed using the cloud container so will need to be encrypted with the key that is used by the container.

Additional properties can be passed through to the driver using the ```cloud.database.properties.``` prefix.

You may want to specify the database (if the default for the user is incorrect), or use Integerated Security (rather than explicit SQL credentials). An example of both of these is below.

* ```cloud.database.properties.databaseName``` - The database name which the queries will use.
* ```cloud.database.properties.integratedSecurity``` - Set to ```true``` to use Windows Authentication
* ```cloud.database.properties.encrypt``` - Set to ```false``` if MSSQL is using an untrusted certificate
* ```cloud.database.properties.domain``` - Active Directory Domain FQDN for Integrated Auth
* ```cloud.database.properties.realm``` - Kerberos Realm for Integrated Auth (usually same as the domain above)
* ```cloud.database.properties.authentication``` - Set this to ```NotSpecified```
* ```cloud.database.properties.authenticationScheme``` - Set this to ```NTLM```
Note that the last four configuration options above can be omitted for standard SQL authentication.

Mutliple queries can be defined. These are based on the queryType= parameter passed as part of the URL.
The ```<ID>``` in the configuration parameters below refer to the ID passed in from the URL. These are case sensitive.

* ```query.<ID>.sql``` - SQL Query to execute to find the record. It should be in the format of ```SELECT COLUMN1, COLUMN2, COLUMN3 FROM TABLE WHERE PRIMARY_ID=?``` - A single question mark must be provided. The ```RECORDID``` from the URL will be substituted into the ? parameter. Table joins and other complex structures can be used, each column name should be uniquely aliased. 
* ```query.<ID>.search-data-type``` - One of ```TEXT/NUMBER/TIMESTAMP``` - this is the data type of the Search column in the query above. It controls how the Record ID is passed into the Prepared Statement.

For every column that needs to be returned, a column definition should be set up.
These are referenced by the ```<COLID>``` part of the configuration parameter. This should match the column alias from the query above.
At minimum, every column requires the ```enabled``` configuration item, all the others are optional.

* ```query.<ID>.column.<COLID>.enabled``` - Required - ```true``` if the column is used, anything else is considered false.
* ```query.<ID>.column.<COLID>.data-type``` - One of ```TEXT/NUMBER/TIMESTAMP``` - default is TEXT. This is the data type retrieved from the database. It needs to match the column definition.
* ```query.<ID>.column.<COLID>.json-field``` - By default, the column will map to the JSON data as the name of the column verbatim. If this parameter is supplied, it will remap the data to the field named here.

Some database schemas store data not particularly useful to Genesys Cloud - so there is the ability to remap the data for a column based on an enum. A list of value to value mappings can be defined to remap the data

* ```query.<ID>.column.<COLID>.enum.<INVALUE1>``` - When <INVALUE1> is detected in <COLID>, it will be replaced by the value of this configuration option
* ```query.<ID>.column.<COLID>.enum.<INVALUE2>``` - When <INVALUE1> is detected in <COLID>, it will be replaced by the value of this configuration option


#### Configuration Example ####

```
cloud.database.url=jdbc:sqlserver://sql2022db.domain.com:1433
cloud.database.username=MyDBUserName
cloud.database.password=ENC:SomeEncryptedPassword
cloud.database.properties.encrypt=true
cloud.database.properties.databaseName=MGMT_DB
cloud.database.properties.domain=ad.domain.local
cloud.database.properties.realm=ad.domain.local
cloud.database.properties.authentication=NotSpecified
cloud.database.properties.authenticationScheme=NTLM
cloud.database.properties.integratedSecurity=true

# A basic query for a user. URL would be http://hostname/api/v1/record/123?queryType=USERLOOKUP to look up user id #123

query.USERLOOKUP.sql=SELECT USER_NAME, EMAIL, ACTIVATION_DATE, STATUS_ID FROM USER_TABLE WHERE USERID=?
query.USERLOOKUP.search-data-type=NUMBER

# Data type is optional, but we specify it here
query.USERLOOKUP.column.USER_NAME.data-type=TEXT
query.USERLOOKUP.column.USER_NAME.enabled=true

# Rely on the default text data type
query.USERLOOKUP.column.EMAIL.enabled=true

# A timestamp data type
query.USERLOOKUP.column.ACTIVATION_DATE.data-type=TIMESTAMP
query.USERLOOKUP.column.ACTIVATION_DATE.enabled=true

# A number data type with an enum and column remap
query.USERLOOKUP.column.STATUS_ID.data-type=NUMBER
query.USERLOOKUP.column.STATUS_ID.enabled=true

# Map the 3 status values to human-readable text
query.USERLOOKUP.column.STATUS_ID.enum.1=Enabled
query.USERLOOKUP.column.STATUS_ID.enum.2=Disabled
query.USERLOOKUP.column.STATUS_ID.enum.3=Locked

# And give the column a more useful description
query.USERLOOKUP.column.STATUS_ID.json-field=User_Status_Description
```
