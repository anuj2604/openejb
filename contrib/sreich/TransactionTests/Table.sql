drop table TRANSACTIONTESTS cascade constraints;

CREATE TABLE TRANSACTIONTESTS (ID CHAR(30) NOT NULL, VALUE CHAR(30) );

ALTER TABLE TRANSACTIONTESTS ADD PRIMARY KEY (ID);

