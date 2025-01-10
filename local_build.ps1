
./mvnw clean install "-DskipTests" "-Dquarkus.container-image.build=true"
$id=docker images --filter "reference=falx/monolith" -q
docker tag $id localhost:5000/mykvasir:latest
docker push localhost:5000/mykvasir:latest
