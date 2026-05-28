pip install pyzeebe [--proxy]

Fill in config.ini with your credentials (you can copy the Spring configuration from your client).

Some simplifications to the code have been made against best practices for the sake of making it more simple for the purposes of the training(e.g. declaring the zeebe_client as global instead of a dependency injection).