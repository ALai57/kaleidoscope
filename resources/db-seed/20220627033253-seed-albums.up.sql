INSERT INTO albums VALUES
('7c72e23f-6cfe-4f75-adcf-adc39a758dc6', 'My first album', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z', 'This is the first album I made.', '4a3db5ec-358c-4e36-9f19-3e0193001ff4'),
('5820b2c4-a29f-4263-ad85-d6f95460cc34', 'My second album', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z', 'This is the second album I made.', 'f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4'),
('5820b2c4-a29f-4263-ad85-d6f95460cc30', 'My third album', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z', 'This is the second album I made.', NULL);

--;;

INSERT INTO photos VALUES
('4a3db5ec-358c-4e36-9f19-3e0193001ff4', 'My first photo',  '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z', 'andrewslai.localhost'),
('f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4', 'My second photo', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z', 'andrewslai.localhost'),
('bb854ba0-974c-46dc-b403-cbfd0f36e88f', 'My third photo',  '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z', 'andrewslai.localhost');

--;;

INSERT INTO photo_versions VALUES
('4a3db5ec-358c-4e36-9f19-111111111111', '4a3db5ec-358c-4e36-9f19-3e0193001ff4', 'media/processed/1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg', '1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg',  's3', 'wedding', 'thumbnail', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z'),
('4a3db5ec-358c-4e36-9f19-111111111112', '4a3db5ec-358c-4e36-9f19-3e0193001ff4', 'media/processed/1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg', '1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg',  's3', 'wedding', 'raw',       '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z'),
('4a3db5ec-358c-4e36-9f19-222222222222', '4a3db5ec-358c-4e36-9f19-3e0193001ff4', 'media/processed/20210422_134816 (2)-500.jpg',                  '20210422_134816 (2)-500.jpg',                   's3', 'wedding', 'thumbnail', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z'),
('4a3db5ec-358c-4e36-9f19-222222222223', '4a3db5ec-358c-4e36-9f19-3e0193001ff4', 'media/processed/20210422_134816 (2)-500.jpg',                  '20210422_134816 (2)-500.jpg',                   's3', 'wedding', 'raw',       '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z'),
('4a3db5ec-358c-4e36-9f19-333333333333', '4a3db5ec-358c-4e36-9f19-3e0193001ff4', 'media/processed/20210422_134824 (2)-500.jpg',                  '20210422_134824 (2)-500.jpg',                   's3', 'wedding', 'thumbnail', '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z'),
('4a3db5ec-358c-4e36-9f19-333333333334', '4a3db5ec-358c-4e36-9f19-3e0193001ff4', 'media/processed/20210422_134824 (2)-500.jpg',                  '20210422_134824 (2)-500.jpg',                   's3', 'wedding', 'raw',       '2022-02-01T00:00:00Z', '2022-02-01T00:00:00Z');
