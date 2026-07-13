ALTER TABLE lunch_poll_vote
DROP CONSTRAINT lunch_poll_vote_pkey;

ALTER TABLE lunch_poll_vote
    ADD PRIMARY KEY (poll_id, user_id, option_id);

CREATE TABLE lunch_poll_add_mode (
                                     poll_id BIGINT NOT NULL,
                                     user_id BIGINT NOT NULL,
                                     PRIMARY KEY (poll_id, user_id)
);