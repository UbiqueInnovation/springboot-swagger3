# springboot-swagger3
Generate OpenAPI 3 YAML out of SpringBoot controllers

## Usage
Add the dependencies to your pom.xml and then add the following to the plugins section:
```
<plugin>
                <groupId>ch.ubique.openapi</groupId>
                <artifactId>springboot-swagger-3</artifactId>
                <version>1.1.1</version>

                <executions>
                    <execution>
                        <phase>compile</phase>
                        <goals>
                            <goal>springboot-swagger-3</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <apiVersion>1.0-develop</apiVersion>
                    <basePackages>
                      <basePackage>com.my.models</basePackage>
                    </basePackages>
                    <controllers>
                        <controller>com.example.controller</controller>
                    </controllers>
                    <description>Example API forr some test stuff</description>
                    <apiUrls>
                        <apiUrl>https://app-test.example.com</apiUrl>
                        <apiUrl>https://app-prod.example.com</apiUrl>
                    </apiUrls>
                    <title>EXAMPLE API</title>
                </configuration>
            </plugin>
```
