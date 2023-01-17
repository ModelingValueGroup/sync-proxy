## make this proxy work via KeyCloak
I have no idea currently how to make this work with KeyCloak.
We currently use a full duplex connection over a TCP socket to send and receive data.
If we want to use KeyCloak we need to use a HTTP connection.
An HTTP connection does not allow for full duplex communication.

The technology to use would be **WebSockets**.
Another technology that might be relevant is **STOMP** and **SockJS**.

Some relevants resources:
- https://www.baeldung.com/spring-websockets-send-message-to-user
- https://github.com/stakater-lab/spring-web-socket-keycloak/tree/master/application
- https://github.com/gabrielpulga/spring-boot-websocket
- https://github.com/khanhhua/full-duplex-chat/tree/master/app/src
- https://stackoverflow.com/questions/50573461/spring-websockets-authentication-with-spring-security-and-keycloak
- https://stackoverflow.com/a/33483232

I started a discussion on: https://github.com/keycloak/keycloak/discussions/16503

