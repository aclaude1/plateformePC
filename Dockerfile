#import de l'image sur laquelle se baser
FROM alexandreinsa/base-plateforme:latest
MAINTAINER Alexandre Claude <alexandre.claude@insa-lyon.fr>

#On s'attend a recevoir en parametre le nom du repertoire ou se trouve le driver de tests ainsi que la correction
ARG driver_dir
ADD $driver_dir /java/drivers

#Changement de repertoire de travail
WORKDIR /java
RUN mkdir build/

ENTRYPOINT javac -cp ./ -d build/ */*.java && java -cp build/ Driver_tests >> ./etudiant/driver_result.txt
