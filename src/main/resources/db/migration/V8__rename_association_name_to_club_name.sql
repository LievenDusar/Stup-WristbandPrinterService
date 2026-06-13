-- Rename association_name -> club_name to match the Symfony "clubName" property.
-- A RENAME preserves all existing values; the entity field is now PrintJobEntity.clubName.
ALTER TABLE print_jobs RENAME COLUMN association_name TO club_name;
