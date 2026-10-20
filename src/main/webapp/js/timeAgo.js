function timeAgo(dateString) {
  const now = new Date();
  const updated = new Date(dateString);
  const seconds = Math.floor((now - updated) / 1000);

  const intervals = {
    year: 31536000,
    month: 2592000,
    day: 86400,
    hour: 3600,
    minute: 60,
    second: 1,
  };

  for (let [unit, value] of Object.entries(intervals)) {
    const amount = Math.floor(seconds / value);
    if (amount >= 1) {
      const plural = amount > 1 ? "s" : "";
      return `${amount} ${unit}${plural} ago`;
    }
  }

  return "Just now";
}

document.addEventListener("DOMContentLoaded", function () {
  document.querySelectorAll(".time-elapsed").forEach((el) => {
    const updatedAt = el.dataset.updated;
    el.textContent = timeAgo(updatedAt);
  });
});
