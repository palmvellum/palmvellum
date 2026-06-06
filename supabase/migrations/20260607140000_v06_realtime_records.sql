-- Add public.records to the supabase_realtime publication so Realtime
-- channels can deliver postgres_changes for it.
--
-- Without this, the DateBook component's new 'records' channel (which
-- listens for type=todo UPDATEs so the calendar grid removes a to-do
-- the moment the user ticks its checkbox in TodoList) would never
-- fire — postgres_changes only delivers rows from tables that the
-- publication includes.
ALTER PUBLICATION supabase_realtime ADD TABLE public.records;
