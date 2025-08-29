# BFH Java Qualifier – Auto Submitter


---

## 🚀 How to Build & Run

### Prerequisites
- Java 17+
- Maven 3.9+

### Configuration
Edit `src/main/resources/application.yml`:

```yaml
app:
  name: "Your Name"
  regNo: "REG12347"        # must end with 2 digits
  email: "your.email@domain.com"
  submit: true             # set to false for dry run (no submission)
