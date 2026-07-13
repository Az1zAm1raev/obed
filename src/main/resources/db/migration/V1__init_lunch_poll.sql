CREATE TABLE lunch_poll (
                            id BIGSERIAL PRIMARY KEY,
                            chat_id BIGINT NOT NULL,
                            message_id BIGINT,
                            title TEXT NOT NULL,
                            active BOOLEAN NOT NULL DEFAULT TRUE,
                            created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE lunch_poll_option (
                                   id BIGSERIAL PRIMARY KEY,
                                   poll_id BIGINT NOT NULL REFERENCES lunch_poll(id) ON DELETE CASCADE,
                                   number INT NOT NULL,
                                   text TEXT NOT NULL,
                                   UNIQUE (poll_id, number)
);

CREATE TABLE lunch_poll_vote (
                                 poll_id BIGINT NOT NULL REFERENCES lunch_poll(id) ON DELETE CASCADE,
                                 user_id BIGINT NOT NULL,
                                 username TEXT,
                                 full_name TEXT,
                                 option_id BIGINT NOT NULL REFERENCES lunch_poll_option(id),
                                 voted_at TIMESTAMP NOT NULL DEFAULT now(),
                                 PRIMARY KEY (poll_id, user_id)
);