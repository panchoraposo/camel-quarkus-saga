package com.redhat.demo.bootstrap;

import java.util.List;

import com.redhat.demo.camel.saga.repository.UserRepository;
import com.redhat.demo.entity.User;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class UserSeeder {

    @Inject
    UserRepository userRepository;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (userRepository.count() > 0) {
            return;
        }

        // Ensure Keycloak demo users exist in the order DB.
        List<User> users = List.of(
                new User(null, "johndoe", "johndoe@example.com", 100.50),
                new User(null, "janedoe", "janedoe@example.com", 200.75),
                new User(null, "alice", "alice@example.com", 150.00),
                new User(null, "bob", "bob@example.com", 300.25));

        userRepository.persist(users);
    }
}

