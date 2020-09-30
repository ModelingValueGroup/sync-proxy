# template-java
Skeleton for a java project.

## maven dependencies
To get all the dependencies in your ```lib``` folder: use the following commands:
````bash
mvn dependency:copy-dependencies -Dmdep.stripVersion=true -DoutputDirectory=lib
mvn dependency:copy-dependencies -Dmdep.stripVersion=true -DoutputDirectory=lib -Dclassifier=javadoc
mvn dependency:copy-dependencies -Dmdep.stripVersion=true -DoutputDirectory=lib -Dclassifier=sources
````
