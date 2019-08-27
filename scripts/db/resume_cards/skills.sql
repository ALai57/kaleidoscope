CREATE TABLE skills(
       id INT,
       name text,
       url text,
       image_url text,
       description text,
       skill_category text
       );
INSERT INTO skills VALUES
       (1, 'Periscope Data', '', 'images/periscope-logo.svg', 'Analytics tool for dashboarding and data visualization.', 'Analytics Tools'),
       (2, 'Sumologic', '', 'images/sumologic-logo.svg', 'Analytics tool for collecting/analyzing event logs.', 'Analytics Tools'),
       (3, 'Heap Analytics', '', 'images/heap-logo.svg', 'Analytics tool for collecting/analyzing user behavior data and funnel conversion.', 'Analytics Tools'),
       (4, 'Jupyter Notebooks', '', 'images/jupyter-logo.svg', 'Analytics tool for data analytics/data science.', 'Analytics Tools'),
       (5, 'Pandas', '', 'images/pandas-logo.svg', 'Python package for manipulating data.', 'Analytics Tools');
