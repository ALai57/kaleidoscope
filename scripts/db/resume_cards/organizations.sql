CREATE TABLE organizations(
       id INT,
       name text,
       url text,
       image_url text,
       description text
       );
INSERT INTO organizations VALUES
       (1, 'HELIX', 'https://helix.northwestern.edu', 'images/helix-logo.svg', 'Northwestern science outreach magazine'),
       (2, 'YMCA', 'http://www.mcgawymca.org/', 'images/ymca-logo.svg', 'YMCA'),
       (3, 'VAI', 'www.hnvi.org', 'images/vai-logo.svg', 'Vietnamese Association of Illinois'),
       (4, 'ChiPy', 'https://www.chipy.org', 'images/chipy-logo.svg', 'Chicago Python User Group');
