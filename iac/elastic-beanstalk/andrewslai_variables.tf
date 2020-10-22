
variable "default_vpc" {}
variable "subnets" {}
variable "security_groups" {}

variable "ANDREWSLAI_DB_PASSWORD" {
  description = "Database password"
}

variable "ANDREWSLAI_DB_USER" {
  description = "Database username"
}

variable "ANDREWSLAI_DB_NAME" {
  description = "Database password"
}

variable "ANDREWSLAI_DB_HOST" {
  description = "Database host url"
}

variable "ANDREWSLAI_DB_PORT" {
  description = "Database port"
}
