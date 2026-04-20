package com.redhat.demo.resource;

import java.util.List;

import com.redhat.demo.camel.saga.repository.UserRepository;
import com.redhat.demo.entity.User;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/users")
public class UserResource {

    @Inject
    UserRepository userRepository;

    @Inject
    SecurityIdentity securityIdentity;

    @GET
    public List<User> getAllUsers() {
        return userRepository.listAll();
    }

    @GET
    @Path("/me")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response me() {
        String username = securityIdentity != null && securityIdentity.getPrincipal() != null
                ? securityIdentity.getPrincipal().getName()
                : null;
        if (username == null || username.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        User user = userRepository.find("username", username).firstResult();
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(user).build();
    }
    
}