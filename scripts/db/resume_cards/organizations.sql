CREATE TABLE IF NOT EXISTS organizations(
       id INT,
       name text,
       url text,
       image_url text,
       description text
       );
ALTER TABLE organizations ADD CONSTRAINT unique_organizations UNIQUE (name);
INSERT INTO organizations VALUES
       (1, 'HELIX', 'https://helix.northwestern.edu', 'images/nu-helix-logo.svg', 'Northwestern science outreach magazine'),
       (2, 'YMCA', 'http://www.mcgawymca.org/', 'images/ymca-logo.svg', 'YMCA'),
       (3, 'VAI', 'www.hnvi.org', 'images/vai-logo.svg', 'Vietnamese Association of Illinois'),
       (4, 'ChiPy', 'https://www.chipy.org', 'images/chipy-logo.svg', 'Chicago Python User Group')
       ON CONFLICT (name) DO UPDATE
       SET
         id = EXCLUDED.id,
         name = EXCLUDED.name,
         url = EXCLUDED.url,
         image_url = EXCLUDED.image_url,
         description = EXCLUDED.description;
