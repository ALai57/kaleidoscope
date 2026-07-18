
INSERT INTO portfolio_entries (name, type, url, image_url, description, tags, hostname) VALUES
('HELIX'                , 'organization', 'https://helix.northwestern.edu'                                     , 'images/nu-helix-logo.svg'             , 'Northwestern science outreach magazine', '', 'andrewslai.com'),
('YMCA'                 , 'organization', 'http://www.mcgawymca.org/'                                          , 'images/ymca-logo.svg'                 , 'YMCA'                                  , '', 'andrewslai.com'),
('VAI'                  , 'organization', 'www.hnvi.org'                                                       , 'images/vai-logo.svg'                  , 'Vietnamese Association of Illinois'    , '', 'andrewslai.com'),
('ChiPy'                , 'organization', 'https://www.chipy.org'                                              , 'images/chipy-logo.svg'                , 'Chicago Python User Group'             , '', 'andrewslai.com'),
('ChiHackNight'         , 'organization', 'https://chihacknight.org/'                                          , 'images/chi-hack-night-logo.svg'       , 'Chicago Civic Hacking'                 , '', 'andrewslai.com'),
('Center for Leadership', 'organization', 'https://lead.northwestern.edu/leadership/index.html'                , 'images/center-for-leadership-logo.svg', 'Northwestern Center for Leadership'    , '', 'andrewslai.com'),
('MGLC'                 , 'organization', 'https://www.mccormick.northwestern.edu/graduate-leadership-council/', 'images/mglc-logo.svg'                 , 'McCormick Graduate Leadership Council' , '', 'andrewslai.com');

--;;

INSERT INTO portfolio_entries (name, type, url, image_url, description, tags, hostname) VALUES
('Periscope Data'   , 'skill', '', 'images/periscope-logo.svg', 'Analytics tool for dashboarding and data visualization....'                       , 'Analytics Tools', 'andrewslai.com'),
('Sumologic'        , 'skill', '', 'images/sumologic-logo.svg', 'Analytics tool for collecting/analyzing event logs.'                              , 'Analytics Tools', 'andrewslai.com'),
('Heap Analytics'   , 'skill', '', 'images/heap-logo.svg'     , 'Analytics tool for collecting/analyzing user behavior data and funnel conversion.', 'Analytics Tools', 'andrewslai.com'),
('Jupyter Notebooks', 'skill', '', 'images/jupyter-logo.svg'  , 'Analytics tool for data analytics/data science.'                                  , 'Analytics Tools', 'andrewslai.com'),
('Pandas'           , 'skill', '', 'images/pandas-logo.svg'   , 'Python package for manipulating data.'                                            , 'Analytics Tools', 'andrewslai.com');

--;;

INSERT INTO portfolio_entries (name, type, url, image_url, description, tags, hostname) VALUES
('The experiment that shocked the world', 'project', 'https://helix.northwestern.edu/article/experiment-shocked-world'                               , 'images/nu-helix-logo.svg', 'A short article on Luigi Galvani and his discovery of animal electricity.', '', 'andrewslai.com'),
('Project SOAR'                         , 'project', 'http://www.mcgawymca.org/youth-teens/after-school-care-activities/mentoring/project-soar-news/', 'images/ymca-logo.svg'    , 'Youth mentoring through the YMCA'                                         , '', 'andrewslai.com'),
('Teaching ESL and Citizenship'         , 'project', 'www.hnvi.org'                                                                                  , 'images/vai-logo.svg'     , 'Teaching conversational English and preparation for the citizenship exam.', '', 'andrewslai.com'),
('ChiPy Mentorship Program'             , 'project', 'https://chipymentor.org'                                                                       , 'images/chipy-logo.svg'   , 'Mentee in a Python mentorship program.'                                   , '', 'andrewslai.com');

--;;

INSERT INTO portfolio_links (name_1, relation, name_2, description, hostname) VALUES
('The experiment that shocked the world', 'wrote for'   , 'HELIX'                   , '', 'andrewslai.com'),
('Pandas'                               , 'used at'     , 'ChiPy'                   , '', 'andrewslai.com'),
('Jupyter Notebooks'                    , 'learned at'  , 'ChiPy Mentorship Program', '', 'andrewslai.com'),
('Teaching ESL and Citizenship'         , 'performed at', 'VAI'                     , '', 'andrewslai.com');
