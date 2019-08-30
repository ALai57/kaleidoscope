CREATE TABLE IF NOT EXISTS projects(
       id INT,
       name text,
       url text,
       image_url text,
       description text,
       organization_names text[],
       skills_names JSONB[]
       );
ALTER TABLE projects ADD CONSTRAINT unique_projects UNIQUE (name);
INSERT INTO projects VALUES
       (1, 'The experiment that shocked the world', 'https://helix.northwestern.edu/article/experiment-shocked-world', 'images/nu-helix-logo.svg', 'A short article on Luigi Galvani and his discovery of animal electricity.', '{HELIX}', array['{"Heap Analytics": "Did something with Heap"}'::json]),
       (2, 'Project SOAR', 'http://www.mcgawymca.org/youth-teens/after-school-care-activities/mentoring/project-soar-news/', 'images/ymca-logo.svg', 'Youth mentoring through the YMCA', '{YMCA}', array['{"Sumologic": "Did something with Sumologic"}'::json]),
       (3, 'Teaching ESL and Citizenship', 'www.hnvi.org', 'images/vai-logo.svg', 'Teaching conversational English and preparation for the citizenship exam.', '{VAI}', array['{"Pandas": "Did something with Pandas"}'::json]),
       (4, 'ChiPy Mentorship Program', 'https://chipymentor.org', 'images/chipy-logo.svg', 'Mentee in a Python mentorship program.', '{ChiPy}', array['{"Pandas": "Did something with Pandas"}'::json])
       ON CONFLICT (name) DO UPDATE
       SET
         id = EXCLUDED.id,
         name = EXCLUDED.name,
         url = EXCLUDED.url,
         image_url = EXCLUDED.image_url,
         description = EXCLUDED.description,
         organization_names = EXCLUDED.organization_names,
         skills_names = EXCLUDED.skills_names;


