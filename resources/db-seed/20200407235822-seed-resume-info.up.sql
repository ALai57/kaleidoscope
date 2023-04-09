
INSERT INTO portfolio_entries VALUES
(DEFAULT, 'HELIX'                , 'organization', 'https://helix.northwestern.edu'                                     , 'images/nu-helix-logo.svg'             , 'Northwestern science outreach magazine', ''),
(DEFAULT, 'YMCA'                 , 'organization', 'http://www.mcgawymca.org/'                                          , 'images/ymca-logo.svg'                 , 'YMCA'                                  , ''),
(DEFAULT, 'VAI'                  , 'organization', 'www.hnvi.org'                                                       , 'images/vai-logo.svg'                  , 'Vietnamese Association of Illinois'    , ''),
(DEFAULT, 'ChiPy'                , 'organization', 'https://www.chipy.org'                                              , 'images/chipy-logo.svg'                , 'Chicago Python User Group'             , ''),
(DEFAULT, 'ChiHackNight'         , 'organization', 'https://chihacknight.org/'                                          , 'images/chi-hack-night-logo.svg'       , 'Chicago Civic Hacking'                 , ''),
(DEFAULT, 'Center for Leadership', 'organization', 'https://lead.northwestern.edu/leadership/index.html'                , 'images/center-for-leadership-logo.svg', 'Northwestern Center for Leadership'    , ''),
(DEFAULT, 'MGLC'                 , 'organization', 'https://www.mccormick.northwestern.edu/graduate-leadership-council/', 'images/mglc-logo.svg'                 , 'McCormick Graduate Leadership Council' , '');

--;;

INSERT INTO portfolio_entries VALUES
(DEFAULT, 'Periscope Data'   , 'skill', '', 'images/periscope-logo.svg', 'Analytics tool for dashboarding and data visualization....'                       , 'Analytics Tools'),
(DEFAULT, 'Sumologic'        , 'skill', '', 'images/sumologic-logo.svg', 'Analytics tool for collecting/analyzing event logs.'                              , 'Analytics Tools'),
(DEFAULT, 'Heap Analytics'   , 'skill', '', 'images/heap-logo.svg'     , 'Analytics tool for collecting/analyzing user behavior data and funnel conversion.', 'Analytics Tools'),
(DEFAULT, 'Jupyter Notebooks', 'skill', '', 'images/jupyter-logo.svg'  , 'Analytics tool for data analytics/data science.'                                  , 'Analytics Tools'),
(DEFAULT, 'Pandas'           , 'skill', '', 'images/pandas-logo.svg'   , 'Python package for manipulating data.'                                            , 'Analytics Tools');

--;;

INSERT INTO portfolio_entries VALUES
(DEFAULT, 'The experiment that shocked the world', 'project', 'https://helix.northwestern.edu/article/experiment-shocked-world'                               , 'images/nu-helix-logo.svg', 'A short article on Luigi Galvani and his discovery of animal electricity.', ''),
(DEFAULT, 'Project SOAR'                         , 'project', 'http://www.mcgawymca.org/youth-teens/after-school-care-activities/mentoring/project-soar-news/', 'images/ymca-logo.svg'    , 'Youth mentoring through the YMCA'                                         , ''),
(DEFAULT, 'Teaching ESL and Citizenship'         , 'project', 'www.hnvi.org'                                                                                  , 'images/vai-logo.svg'     , 'Teaching conversational English and preparation for the citizenship exam.', ''),
(DEFAULT, 'ChiPy Mentorship Program'             , 'project', 'https://chipymentor.org'                                                                       , 'images/chipy-logo.svg'   , 'Mentee in a Python mentorship program.'                                   , '');

--;;

INSERT INTO portfolio_links VALUES
(DEFAULT, 'The experiment that shocked the world', 'wrote for'   , 'HELIX'                   , ''),
(DEFAULT, 'Pandas'                               , 'used at'     , 'ChiPy'                   , ''),
(DEFAULT, 'Jupyter Notebooks'                    , 'learned at'  , 'ChiPy Mentorship Program', ''),
(DEFAULT, 'Teaching ESL and Citizenship'         , 'performed at', 'VAI'                     , '');
