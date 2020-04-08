
INSERT INTO organizations VALUES
       (1, 'HELIX', 'https://helix.northwestern.edu', 'images/nu-helix-logo.svg', 'Northwestern science outreach magazine'),
       (2, 'YMCA', 'http://www.mcgawymca.org/', 'images/ymca-logo.svg', 'YMCA'),
       (3, 'VAI', 'www.hnvi.org', 'images/vai-logo.svg', 'Vietnamese Association of Illinois'),
       (4, 'ChiPy', 'https://www.chipy.org', 'images/chipy-logo.svg', 'Chicago Python User Group'),
       (5, 'ChiHackNight', 'https://chihacknight.org/', 'images/chi-hack-night-logo.svg', 'Chicago Civic Hacking'),
       (6, 'Center for Leadership', 'https://lead.northwestern.edu/leadership/index.html', 'images/center-for-leadership-logo.svg', 'Northwestern Center for Leadership'),
       (7, 'MGLC', 'https://www.mccormick.northwestern.edu/graduate-leadership-council/', 'images/mglc-logo.svg', 'McCormick Graduate Leadership Council (Engineering Graduate school leadership)');

--;;


INSERT INTO projects VALUES
       (1, 'The experiment that shocked the world', 'https://helix.northwestern.edu/article/experiment-shocked-world', 'images/nu-helix-logo.svg', 'A short article on Luigi Galvani and his discovery of animal electricity.', '{HELIX}', array['{"Heap Analytics": "Did something with Heap"}'::json]),
       (2, 'Project SOAR', 'http://www.mcgawymca.org/youth-teens/after-school-care-activities/mentoring/project-soar-news/', 'images/ymca-logo.svg', 'Youth mentoring through the YMCA', '{YMCA}', array['{"Sumologic": "Did something with Sumologic"}'::json]),
       (3, 'Teaching ESL and Citizenship', 'www.hnvi.org', 'images/vai-logo.svg', 'Teaching conversational English and preparation for the citizenship exam.', '{VAI}', array['{"Pandas": "Did something with Pandas"}'::json]),
       (4, 'ChiPy Mentorship Program', 'https://chipymentor.org', 'images/chipy-logo.svg', 'Mentee in a Python mentorship program.', '{ChiPy}', array['{"Pandas": "Did something with Pandas"}'::json]);

--;;


INSERT INTO skills VALUES
(1, 'Periscope Data', '', 'images/periscope-logo.svg', 'Analytics tool for dashboarding and data visualization....', 'Analytics Tools'),
(2, 'Sumologic', '', 'images/sumologic-logo.svg', 'Analytics tool for collecting/analyzing event logs.', 'Analytics Tools'),
(3, 'Heap Analytics', '', 'images/heap-logo.svg', 'Analytics tool for collecting/analyzing user behavior data and funnel conversion.', 'Analytics Tools'),
(4, 'Jupyter Notebooks', '', 'images/jupyter-logo.svg', 'Analytics tool for data analytics/data science.', 'Analytics Tools'),
(5, 'Pandas', '', 'images/pandas-logo.svg', 'Python package for manipulating data.', 'Analytics Tools');
