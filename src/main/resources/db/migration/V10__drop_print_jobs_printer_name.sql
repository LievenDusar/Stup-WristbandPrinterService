-- printers.display_name is now the source of truth and PrintJobEntity no longer maps
-- printer_name (this commit), so drop the duplicated column. The FK on printer_id was
-- already added in V9.
ALTER TABLE print_jobs DROP COLUMN printer_name;
