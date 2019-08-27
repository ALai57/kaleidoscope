CREATE TABLE projects(
       id INT,
       name text,
       url text,
       image_url text,
       description text,
       organization_name text
       );
INSERT INTO projects VALUES
       (1, 'The experiment that shocked the world', 'https://helix.northwestern.edu/article/experiment-shocked-world', 'images/helix-logo.svg', 'A short article on Luigi Galvani and his discovery of animal electricity.', 'HELIX'),
       (2, 'Project SOAR', 'http://www.mcgawymca.org/youth-teens/after-school-care-activities/mentoring/project-soar-news/',
       'images/ymca-logo.svg', 'Youth mentoring through the YMCA', 'YMCA'),
       (3, 'Teaching ESL and Citizenship', 'www.hnvi.org', 'images/vai-logo.svg', 'Teaching conversational English and preparation for the citizenship exam.', 'VAI'),
       (4, 'ChiPy Mentorship Program', 'https://chipymentor.org', 'images/chipy-logo.svg', 'Mentee in a Python mentorship program.', 'ChiPy');
