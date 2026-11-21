alter table public.profiles
  add column if not exists kyc_bvn_verified_at timestamptz,
  add column if not exists kyc_nin_verified_at timestamptz,
  add column if not exists kyc_provider text,
  add column if not exists kyc_verified_at timestamptz;

comment on column public.profiles.kyc_bvn_verified_at is 'Timestamp of successful BVN face-bound verification; never stores the BVN.';
comment on column public.profiles.kyc_nin_verified_at is 'Timestamp of successful NIN face-bound verification; never stores the NIN.';
comment on column public.profiles.kyc_provider is 'Identity verification provider name; no provider response or identity number stored.';
comment on column public.profiles.kyc_verified_at is 'Timestamp when required BVN and NIN checks were both completed.';
